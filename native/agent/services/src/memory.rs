use anyhow::Context;
use anyhow::Result;
use chrono::DateTime;
use chrono::Duration;
use chrono::Utc;
use serde::Deserialize;
use serde::Serialize;
use std::collections::BTreeSet;
use std::fs;
use std::path::Path;
use std::path::PathBuf;

const STALE_AFTER_DAYS: i64 = 7;
const MAX_PROMPT_CHARS: usize = 32_000;

#[derive(Clone, Copy, Debug, Default, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MemoryType {
    #[default]
    User,
    Feedback,
    Project,
    Reference,
    Persona,
    Episodic,
    Instruction,
}

#[derive(Clone, Copy, Debug, Deserialize, Eq, PartialEq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MemoryScope {
    Local,
    Project,
    Global,
}

#[derive(Clone, Debug, Deserialize, Serialize)]
pub struct Memory {
    pub name: String,
    pub description: String,
    #[serde(rename = "type", default)]
    pub memory_type: MemoryType,
    pub created: DateTime<Utc>,
    pub updated: DateTime<Utc>,
    #[serde(skip)]
    pub content: String,
    #[serde(skip)]
    pub stale: bool,
    #[serde(skip)]
    pub scope: Option<MemoryScope>,
}

#[derive(Clone)]
pub struct MemoryManager {
    local_dir: PathBuf,
    project_dir: PathBuf,
    global_dir: PathBuf,
}

impl MemoryManager {
    pub fn new(home: &Path, project_path: &Path) -> Self {
        let project_key = format!(
            "{:x}",
            md5::compute(project_path.to_string_lossy().as_bytes())
        );
        Self {
            local_dir: project_path.join(".tiyo").join("memory"),
            project_dir: home
                .join("projects")
                .join(&project_key[..12.min(project_key.len())])
                .join("memory"),
            global_dir: home.join("memory"),
        }
    }

    pub fn list(&self) -> Vec<Memory> {
        self.list_with_global(true)
    }

    fn list_with_global(&self, include_global: bool) -> Vec<Memory> {
        let mut seen = BTreeSet::new();
        let mut memories = Vec::new();
        for (scope, directory) in self
            .directories()
            .into_iter()
            .filter(|(scope, _)| include_global || *scope != MemoryScope::Global)
        {
            let Ok(entries) = fs::read_dir(directory) else {
                continue;
            };
            let mut paths = entries
                .flatten()
                .map(|entry| entry.path())
                .filter(|path| {
                    path.extension().and_then(|value| value.to_str()) == Some("md")
                        && path.file_name().and_then(|value| value.to_str()) != Some("MEMORY.md")
                })
                .collect::<Vec<_>>();
            paths.sort();
            for path in paths {
                let Ok(mut memory) = read_memory(&path) else {
                    continue;
                };
                if !seen.insert(memory.name.clone()) {
                    continue;
                }
                memory.scope = Some(scope);
                memory.stale = matches!(
                    memory.memory_type,
                    MemoryType::Project | MemoryType::Reference
                ) && Utc::now().signed_duration_since(memory.updated)
                    > Duration::days(STALE_AFTER_DAYS);
                memories.push(memory);
            }
        }
        memories
    }

    pub fn get(&self, name: &str) -> Option<Memory> {
        self.list().into_iter().find(|memory| memory.name == name)
    }

    pub fn search(&self, query: &str, limit: usize) -> Vec<Memory> {
        self.search_with_global(query, limit, true)
    }

    pub fn search_with_global(
        &self,
        query: &str,
        limit: usize,
        include_global: bool,
    ) -> Vec<Memory> {
        let terms = search_terms(query);
        let mut scored = self
            .list_with_global(include_global)
            .into_iter()
            .filter_map(|memory| {
                let name = memory.name.to_lowercase();
                let description = memory.description.to_lowercase();
                let content = memory.content.to_lowercase();
                let score = terms.iter().fold(0usize, |score, term| {
                    score
                        + usize::from(name.contains(term)) * 5
                        + usize::from(description.contains(term)) * 3
                        + usize::from(content.contains(term))
                });
                (score > 0).then_some((score, memory))
            })
            .collect::<Vec<_>>();
        scored.sort_by_key(|item| std::cmp::Reverse(item.0));
        scored
            .into_iter()
            .take(limit.max(1))
            .map(|(_, memory)| memory)
            .collect()
    }

    /// Returns the memories most relevant to this turn in the same Markdown shape used by
    /// `prompt_context`. `byte_budget` is deliberately conservative: a UTF-8 byte can account
    /// for at most one model token in byte-level tokenizers, so the resulting block cannot
    /// exceed the requested token budget even when no provider tokenizer is available here.
    pub fn search_prompt_context(
        &self,
        query: &str,
        limit: usize,
        byte_budget: usize,
        include_global: bool,
    ) -> String {
        const PREFIX: &str = "<relevant_memory>\n";
        const SUFFIX: &str = "</relevant_memory>";

        if byte_budget <= PREFIX.len().saturating_add(SUFFIX.len()) {
            return String::new();
        }
        let memories = self
            .search_with_global(query, limit, include_global)
            .into_iter()
            .filter(|memory| !memory.stale)
            .collect::<Vec<_>>();
        if memories.is_empty() {
            return String::new();
        }

        let content_budget = byte_budget - PREFIX.len() - SUFFIX.len();
        let mut body = String::new();
        for memory in memories {
            let entry = format!(
                "### {}\n_{}_\n\n{}\n\n",
                memory.name, memory.description, memory.content
            );
            if body.len().saturating_add(entry.len()) <= content_budget {
                body.push_str(&entry);
                continue;
            }
            // Results are score-sorted, so anything after this point is lower priority. Keep a
            // useful prefix of the top result only when no complete result fitted the budget.
            if body.is_empty() {
                body.push_str(&truncate_utf8(&entry, content_budget));
            }
            break;
        }
        if body.trim().is_empty() {
            return String::new();
        }

        format!("{PREFIX}{body}{SUFFIX}")
    }

    pub fn save(
        &self,
        scope: MemoryScope,
        name: &str,
        description: &str,
        memory_type: MemoryType,
        content: &str,
    ) -> Result<PathBuf> {
        validate_name(name)?;
        let directory = self.directory(scope);
        fs::create_dir_all(directory)?;
        let path = directory.join(format!("{name}.md"));
        let existing = read_memory(&path).ok();
        let now = Utc::now();
        let memory = Memory {
            name: name.to_owned(),
            description: description.to_owned(),
            memory_type,
            created: existing.as_ref().map_or(now, |memory| memory.created),
            updated: now,
            content: content.to_owned(),
            stale: false,
            scope: Some(scope),
        };
        fs::write(&path, render_memory(&memory))
            .with_context(|| format!("failed to save memory {}", path.display()))?;
        self.refresh_index()?;
        Ok(path)
    }

    pub fn delete(&self, name: &str) -> Result<bool> {
        validate_name(name)?;
        for (_, directory) in self.directories() {
            let path = directory.join(format!("{name}.md"));
            if path.is_file() {
                fs::remove_file(&path)?;
                self.refresh_index()?;
                return Ok(true);
            }
        }
        Ok(false)
    }

    pub fn prompt_context(&self) -> String {
        let mut output = String::new();
        for memory in self.list().into_iter().filter(|memory| !memory.stale) {
            let entry = format!(
                "### {}\n_{}_\n\n{}\n\n",
                memory.name, memory.description, memory.content
            );
            if output.len().saturating_add(entry.len()) > MAX_PROMPT_CHARS {
                break;
            }
            output.push_str(&entry);
        }
        output
    }

    pub fn refresh_index(&self) -> Result<()> {
        let directory = if self.local_dir.is_dir() {
            &self.local_dir
        } else {
            &self.project_dir
        };
        fs::create_dir_all(directory)?;
        let mut lines = vec![
            "# Memory Index".to_owned(),
            "> Auto-generated. Local entries override project and global entries.".to_owned(),
            String::new(),
        ];
        for memory in self.list() {
            lines.push(format!(
                "- [{}](./{}.md) - {}{}",
                memory.name,
                memory.name,
                memory.description,
                if memory.stale { " [stale]" } else { "" }
            ));
        }
        fs::write(directory.join("MEMORY.md"), lines.join("\n"))?;
        Ok(())
    }

    fn directories(&self) -> [(MemoryScope, &Path); 3] {
        [
            (MemoryScope::Local, &self.local_dir),
            (MemoryScope::Project, &self.project_dir),
            (MemoryScope::Global, &self.global_dir),
        ]
    }

    fn directory(&self, scope: MemoryScope) -> &Path {
        match scope {
            MemoryScope::Local => &self.local_dir,
            MemoryScope::Project => &self.project_dir,
            MemoryScope::Global => &self.global_dir,
        }
    }
}

fn validate_name(name: &str) -> Result<()> {
    anyhow::ensure!(
        !name.is_empty()
            && name.len() <= 80
            && name.chars().all(
                |character| character.is_ascii_alphanumeric() || matches!(character, '-' | '_')
            ),
        "memory name must use 1-80 ASCII letters, numbers, hyphens, or underscores"
    );
    Ok(())
}

fn truncate_utf8(value: &str, max_bytes: usize) -> String {
    if value.len() <= max_bytes {
        return value.to_owned();
    }
    let mut end = max_bytes;
    while end > 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
}

fn search_terms(query: &str) -> Vec<String> {
    let mut seen = BTreeSet::new();
    let mut terms = Vec::new();
    for raw_term in query.split(|character: char| {
        !character.is_alphanumeric() && character != '_' && character != '-'
    }) {
        let term = raw_term.to_lowercase();
        let characters = term.chars().collect::<Vec<_>>();
        if characters.len() < 2 {
            continue;
        }
        if seen.insert(term.clone()) {
            terms.push(term);
        }
        // Chinese/Japanese/Korean sentences normally contain no spaces, so treating the whole
        // sentence as one keyword misses a memory such as "导师姓名" for "我导师叫什么".
        // Add overlapping two-character terms while preserving the existing whole-term match.
        if characters.iter().any(|character| !character.is_ascii()) {
            for pair in characters.windows(2) {
                let pair = pair.iter().collect::<String>();
                if seen.insert(pair.clone()) {
                    terms.push(pair);
                }
            }
        }
    }
    terms
}

fn read_memory(path: &Path) -> Result<Memory> {
    let text = fs::read_to_string(path)?;
    let rest = text
        .strip_prefix("---\n")
        .context("memory has no frontmatter")?;
    let (frontmatter, content) = rest
        .split_once("\n---\n")
        .context("memory frontmatter is not closed")?;
    let mut memory: Memory = serde_yaml::from_str(frontmatter)?;
    memory.content = content.trim().to_owned();
    Ok(memory)
}

fn render_memory(memory: &Memory) -> String {
    let frontmatter = serde_yaml::to_string(memory).unwrap_or_default();
    format!("---\n{}---\n\n{}\n", frontmatter, memory.content)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn local_memory_overrides_project_and_global() {
        let home = tempfile::tempdir().expect("home");
        let project = tempfile::tempdir().expect("project");
        let manager = MemoryManager::new(home.path(), project.path());
        manager
            .save(
                MemoryScope::Global,
                "preference",
                "global",
                MemoryType::User,
                "dark",
            )
            .expect("global memory");
        manager
            .save(
                MemoryScope::Local,
                "preference",
                "local",
                MemoryType::User,
                "light",
            )
            .expect("local memory");
        let memories = manager.list();
        assert_eq!(memories.len(), 1);
        assert_eq!(memories[0].content, "light");
        assert_eq!(memories[0].scope, Some(MemoryScope::Local));
        let context = manager.search_prompt_context("preference", 10, 2_000, true);
        assert!(context.contains("light"));
        assert!(!context.contains("dark"));
    }

    #[test]
    fn search_prompt_context_respects_global_privacy_and_budget() {
        let home = tempfile::tempdir().expect("home");
        let project = tempfile::tempdir().expect("project");
        let manager = MemoryManager::new(home.path(), project.path());
        manager
            .save(
                MemoryScope::Global,
                "mentor",
                "导师姓名",
                MemoryType::User,
                "导师叫林老师",
            )
            .expect("global memory");

        assert!(
            manager
                .search_prompt_context("我导师叫什么", 10, 2_000, false)
                .is_empty()
        );
        let full_context = manager.search_prompt_context("我导师叫什么", 10, 2_000, true);
        assert!(full_context.contains("导师叫林老师"));
        let context = manager.search_prompt_context("我导师叫什么", 10, 48, true);
        assert!(!context.is_empty());
        assert!(context.len() <= 48);
        assert!(context.starts_with("<relevant_memory>\n"));
        assert!(context.ends_with("</relevant_memory>"));
    }

    #[test]
    fn search_prompt_context_is_empty_when_nothing_matches() {
        let home = tempfile::tempdir().expect("home");
        let project = tempfile::tempdir().expect("project");
        let manager = MemoryManager::new(home.path(), project.path());
        manager
            .save(
                MemoryScope::Local,
                "mentor",
                "导师姓名",
                MemoryType::User,
                "导师叫林老师",
            )
            .expect("local memory");

        assert!(
            manager
                .search_prompt_context("今天天气", 10, 2_000, true)
                .is_empty()
        );
    }

    #[test]
    fn android_memory_types_roundtrip() {
        // 与 Android 端 TiyoAtomicMemory 的 TYPE_PERSONA/EPISODIC/INSTRUCTION 对齐的固定样例，
        // 保证 Kotlin 写出的 type 字符串能被 Rust 正确解析，不会静默跳过。
        let cases = [
            ("persona", MemoryType::Persona),
            ("episodic", MemoryType::Episodic),
            ("instruction", MemoryType::Instruction),
        ];
        for (wire, expected) in cases {
            let frontmatter = format!(
                "name: sample\ndescription: 跨语言固定样例\ntype: {wire}\ncreated: 2026-08-15T00:00:00Z\nupdated: 2026-08-15T00:00:00Z\n"
            );
            let memory: Memory = serde_yaml::from_str(&frontmatter)
                .unwrap_or_else(|error| panic!("type={wire} 解析失败: {error}"));
            assert_eq!(memory.memory_type, expected, "type={wire} 类型不匹配");
        }
    }
}
