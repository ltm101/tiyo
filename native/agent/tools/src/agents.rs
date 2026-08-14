use async_trait::async_trait;
use serde_json::Value;
use std::collections::BTreeMap;
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;
use tiyo_engine::Agent;
use tiyo_engine::AgentEvent;
use tiyo_engine::AgentObserver;
use tiyo_engine::ApprovalHandler;
use tiyo_engine::ChatMessage;
use tiyo_engine::Session;
use tiyo_engine::ToolCall;
use tiyo_engine::ToolResult;
use tiyo_engine::ToolRuntime;
use tiyo_engine::ToolSpec;
use tiyo_engine::UserInputRequest;
use tiyo_engine::UserInputResponse;
use tiyo_security::AccessMode;
use tiyo_security::HookRunner;
use tiyo_security::SecurityPolicy;
use tiyo_services::HttpModelProvider;
use tiyo_services::MemoryManager;
use tiyo_services::ProviderConfig;
use tokio::sync::Mutex;
use tokio::sync::mpsc;
use tokio::sync::mpsc::error::TrySendError;
use tokio::task::AbortHandle;
use uuid::Uuid;

use crate::CoreTools;

#[derive(Clone, Debug)]
pub struct AgentSnapshot {
    pub id: String,
    pub label: String,
    pub status: String,
    pub task: String,
    pub last_message_id: String,
    pub output: String,
    pub elapsed_ms: u128,
}

struct AgentRecord {
    label: String,
    task: String,
    status: String,
    output: Arc<Mutex<String>>,
    last_message_id: String,
    started: Instant,
    abort: Option<AbortHandle>,
    mailbox: mpsc::Sender<AgentMailboxMessage>,
    pending: usize,
}

struct AgentMailboxMessage {
    id: String,
    content: String,
}

pub struct AgentScheduler {
    cwd: PathBuf,
    home: PathBuf,
    provider: ProviderConfig,
    policy: AccessMode,
    system_prompt: String,
    persistent_memory: bool,
    max_agents: usize,
    agents: Mutex<BTreeMap<String, AgentRecord>>,
}

impl AgentScheduler {
    pub fn new(
        cwd: PathBuf,
        home: PathBuf,
        provider: ProviderConfig,
        policy: AccessMode,
        system_prompt: String,
    ) -> Arc<Self> {
        Arc::new(Self {
            cwd,
            home,
            provider,
            policy,
            system_prompt,
            persistent_memory: true,
            max_agents: 3,
            agents: Mutex::new(BTreeMap::new()),
        })
    }

    pub fn without_persistent_memory(mut self: Arc<Self>) -> Arc<Self> {
        Arc::get_mut(&mut self)
            .expect("agent scheduler must be configured before it is shared")
            .persistent_memory = false;
        self
    }

    pub async fn spawn(
        self: &Arc<Self>,
        task: String,
        parent_messages: &[ChatMessage],
        fork_turns: Option<&str>,
        label: Option<&str>,
        role_instructions: Option<&str>,
    ) -> Result<(String, String), String> {
        if task.trim().is_empty() {
            return Err("agent task must not be empty".into());
        }
        {
            let agents = self.agents.lock().await;
            let retained = agents
                .values()
                .filter(|record| record.status != "closed")
                .count();
            if retained >= self.max_agents {
                return Err(format!(
                    "agent limit reached ({}); close a completed agent before spawning another",
                    self.max_agents
                ));
            }
        }

        let id = Uuid::new_v4().to_string();
        let first_message_id = Uuid::new_v4().to_string();
        let output = Arc::new(Mutex::new(String::new()));
        let messages = fork_history(parent_messages, fork_turns)?;
        let safe_label = label
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .unwrap_or("specialist")
            .chars()
            .take(48)
            .collect::<String>();
        let role_instructions = role_instructions
            .map(str::trim)
            .unwrap_or_default()
            .chars()
            .take(4_000)
            .collect::<String>();
        let (mailbox, receiver) = mpsc::channel(8);
        mailbox
            .try_send(AgentMailboxMessage {
                id: first_message_id.clone(),
                content: task.clone(),
            })
            .map_err(|_| "agent mailbox is unavailable".to_string())?;
        self.agents.lock().await.insert(
            id.clone(),
            AgentRecord {
                label: safe_label.clone(),
                task: task.clone(),
                status: "queued".into(),
                output: Arc::clone(&output),
                last_message_id: first_message_id.clone(),
                started: Instant::now(),
                abort: None,
                mailbox,
                pending: 1,
            },
        );
        let scheduler = Arc::clone(self);
        let id_for_run = id.clone();
        let output_for_run = Arc::clone(&output);
        let join = tokio::spawn(async move {
            let result = scheduler
                .run_agent_loop(
                    &id_for_run,
                    messages,
                    receiver,
                    Arc::clone(&output_for_run),
                    &safe_label,
                    &role_instructions,
                )
                .await;
            if let Err(error) = result {
                let mut output = output_for_run.lock().await;
                if !output.is_empty() {
                    output.push_str("\n\n");
                }
                output.push_str(&format!("agent failed: {error:#}"));
                drop(output);
                if let Some(record) = scheduler.agents.lock().await.get_mut(&id_for_run) {
                    record.status = "failed".into();
                }
            }
        });
        if let Some(record) = self.agents.lock().await.get_mut(&id) {
            record.abort = Some(join.abort_handle());
        }
        Ok((id, first_message_id))
    }

    async fn run_agent_loop(
        self: &Arc<Self>,
        id: &str,
        messages: Vec<ChatMessage>,
        mut receiver: mpsc::Receiver<AgentMailboxMessage>,
        output: Arc<Mutex<String>>,
        label: &str,
        role_instructions: &str,
    ) -> anyhow::Result<()> {
        let mut session = Session::new(&self.provider.id, &self.provider.model, self.cwd.clone());
        session.messages = messages;
        let provider = HttpModelProvider::new(self.provider.clone())?;
        let security = SecurityPolicy::new(&self.cwd, self.policy)?;
        let mut core_tools = CoreTools::new(self.cwd.clone(), security)
            .with_skills_directory(self.home.join("skills"))
            .with_config_home(self.home.clone());
        if self.persistent_memory {
            core_tools = core_tools
                .with_hooks(Arc::new(HookRunner::load(&self.home)?))
                .with_memory(Arc::new(MemoryManager::new(&self.home, &self.cwd)));
        }
        let tools = ScopedAgentTools {
            inner: core_tools,
            allow_all: self.persistent_memory,
        };
        let observer = AgentOutputObserver {
            output: Arc::clone(&output),
        };
        let agent = Agent::new(format!(
            "{}\n\nYou are a delegated Tiyo agent named `{label}`. Work only inside the assigned scope. Return concise findings to the coordinator, do not speak directly to the user, and never import another companion's private memory unless it was explicitly shared in the task.\n{}",
            self.system_prompt, role_instructions
        ));
        while let Some(message) = receiver.recv().await {
            let task = message.content;
            if let Some(record) = self.agents.lock().await.get_mut(id) {
                if record.status == "closed" {
                    break;
                }
                record.task = task.clone();
                record.last_message_id = message.id;
                record.status = "running".into();
                record.started = Instant::now();
                record.pending = record.pending.saturating_sub(1);
            }
            {
                let mut transcript = output.lock().await;
                if !transcript.is_empty() {
                    transcript.push_str("\n\n--- follow-up ---\n");
                }
            }
            let result = agent
                .run_turn(
                    &mut session,
                    task,
                    &provider,
                    &tools,
                    &SubagentApproval,
                    &observer,
                )
                .await;
            let failed = result.as_ref().err().map(ToString::to_string);
            if let Some(error) = &failed {
                let mut transcript = output.lock().await;
                if !transcript.is_empty() {
                    transcript.push_str("\n\n");
                }
                transcript.push_str(&format!("agent turn failed: {error}"));
            }
            if let Some(record) = self.agents.lock().await.get_mut(id) {
                if record.status != "closed" {
                    record.status = if record.pending > 0 {
                        "queued".into()
                    } else if failed.is_some() {
                        "failed".into()
                    } else {
                        "completed".into()
                    };
                }
            }
        }
        Ok(())
    }

    pub async fn send_message(&self, id: &str, message: String) -> Result<String, String> {
        if message.trim().is_empty() {
            return Err("agent message must not be empty".into());
        }
        let message_id = Uuid::new_v4().to_string();
        let (sender, previous_status) = {
            let mut agents = self.agents.lock().await;
            let record = agents
                .get_mut(id)
                .ok_or_else(|| format!("unknown agent: {id}"))?;
            if record.status == "closed" {
                return Err(format!("agent is closed: {id}"));
            }
            let previous_status = record.status.clone();
            record.pending = record.pending.saturating_add(1);
            if record.status != "running" {
                record.status = "queued".into();
            }
            (record.mailbox.clone(), previous_status)
        };
        if let Err(error) = sender.try_send(AgentMailboxMessage {
            id: message_id.clone(),
            content: message,
        }) {
            if let Some(record) = self.agents.lock().await.get_mut(id) {
                record.pending = record.pending.saturating_sub(1);
                if record.pending == 0 && record.status == "queued" {
                    record.status = previous_status;
                }
            }
            return Err(match error {
                TrySendError::Full(_) => format!("agent mailbox is full: {id}"),
                TrySendError::Closed(_) => format!("agent is unavailable: {id}"),
            });
        }
        Ok(message_id)
    }

    pub async fn wait(&self, ids: &[String], timeout_ms: u64) -> Vec<AgentSnapshot> {
        let deadline = tokio::time::Instant::now()
            + std::time::Duration::from_millis(timeout_ms.clamp(10, 3_600_000));
        loop {
            let snapshots = self.snapshots(ids).await;
            if snapshots
                .iter()
                .all(|snapshot| !matches!(snapshot.status.as_str(), "running" | "queued"))
                || tokio::time::Instant::now() >= deadline
            {
                return snapshots;
            }
            tokio::time::sleep(std::time::Duration::from_millis(50)).await;
        }
    }

    pub async fn close(&self, id: &str) -> Result<AgentSnapshot, String> {
        let abort = {
            let mut agents = self.agents.lock().await;
            let record = agents
                .get_mut(id)
                .ok_or_else(|| format!("unknown agent: {id}"))?;
            record.status = "closed".into();
            record.abort.take()
        };
        if let Some(abort) = abort {
            abort.abort();
        }
        self.snapshots(&[id.to_owned()])
            .await
            .into_iter()
            .next()
            .ok_or_else(|| format!("unknown agent: {id}"))
    }

    pub async fn snapshots(&self, ids: &[String]) -> Vec<AgentSnapshot> {
        let agents = self.agents.lock().await;
        let selected = if ids.is_empty() {
            agents.keys().cloned().collect::<Vec<_>>()
        } else {
            ids.to_vec()
        };
        let records = selected
            .into_iter()
            .filter_map(|id| agents.get(&id).map(|record| (id, record)))
            .map(|(id, record)| {
                (
                    id,
                    record.label.clone(),
                    record.status.clone(),
                    record.task.clone(),
                    record.last_message_id.clone(),
                    Arc::clone(&record.output),
                    record.started.elapsed().as_millis(),
                )
            })
            .collect::<Vec<_>>();
        drop(agents);
        let mut snapshots = Vec::with_capacity(records.len());
        for (id, label, status, task, last_message_id, output, elapsed_ms) in records {
            snapshots.push(AgentSnapshot {
                id,
                label,
                status,
                task,
                last_message_id,
                output: output.lock().await.clone(),
                elapsed_ms,
            });
        }
        snapshots
    }
}

fn fork_history(
    messages: &[ChatMessage],
    fork_turns: Option<&str>,
) -> Result<Vec<ChatMessage>, String> {
    match fork_turns.unwrap_or("all") {
        "none" => Ok(Vec::new()),
        "all" => Ok(messages.to_vec()),
        value => {
            let turns = value
                .parse::<usize>()
                .map_err(|_| "fork_turns must be none, all, or a positive integer")?;
            if turns == 0 {
                return Err("fork_turns must be positive".into());
            }
            let user_positions = messages
                .iter()
                .enumerate()
                .filter_map(|(index, message)| {
                    (message.role == tiyo_engine::Role::User).then_some(index)
                })
                .collect::<Vec<_>>();
            let start = user_positions
                .get(user_positions.len().saturating_sub(turns))
                .copied()
                .unwrap_or(0);
            Ok(messages[start..].to_vec())
        }
    }
}

struct AgentOutputObserver {
    output: Arc<Mutex<String>>,
}

impl AgentObserver for AgentOutputObserver {
    fn on_event(&self, event: &AgentEvent) {
        let delta = match event {
            AgentEvent::Text(value) | AgentEvent::TextDelta(value) => Some(value),
            _ => None,
        };
        if let Some(delta) = delta
            && let Ok(mut output) = self.output.try_lock()
        {
            output.push_str(delta);
        }
    }
}

struct SubagentApproval;

/// Collaboration agents on the app receive a hard capability boundary, not a
/// prompt-only privacy request. In particular, workspace files, shell, memory,
/// configuration, and lifecycle hooks stay invisible and non-executable.
struct ScopedAgentTools {
    inner: CoreTools,
    allow_all: bool,
}

impl ScopedAgentTools {
    fn permits(&self, name: &str) -> bool {
        self.allow_all || is_public_collaboration_tool(name)
    }
}

fn is_public_collaboration_tool(name: &str) -> bool {
    matches!(name, "web_search" | "fetch" | "list_skills" | "read_skill")
}

#[async_trait]
impl ToolRuntime for ScopedAgentTools {
    fn specs(&self) -> Vec<ToolSpec> {
        self.inner
            .specs()
            .into_iter()
            .filter(|spec| self.permits(&spec.name))
            .collect()
    }

    async fn call(&self, call: &ToolCall, approval: &dyn ApprovalHandler) -> ToolResult {
        if !self.permits(&call.name) {
            return ToolResult::error(format!(
                "tool is unavailable inside this isolated collaboration: {}",
                call.name
            ));
        }
        self.inner.call(call, approval).await
    }

    async fn lifecycle(&self, event: &str, payload: Value) -> Result<Option<String>, String> {
        if !self.allow_all {
            return Ok(None);
        }
        self.inner.lifecycle(event, payload).await
    }
}

#[async_trait]
impl ApprovalHandler for SubagentApproval {
    async fn approve(&self, _call: &ToolCall, _reason: &str) -> bool {
        false
    }

    async fn request_user_input(&self, _request: &UserInputRequest) -> Option<UserInputResponse> {
        None
    }
}

pub fn snapshots_json(snapshots: &[AgentSnapshot]) -> Value {
    Value::Array(
        snapshots
            .iter()
            .map(|snapshot| {
                serde_json::json!({
                    "id": snapshot.id,
                    "label": snapshot.label,
                    "status": snapshot.status,
                    "task": snapshot.task,
                    "last_message_id": snapshot.last_message_id,
                    "output": snapshot.output,
                    "elapsed_ms": snapshot.elapsed_ms.to_string()
                })
            })
            .collect(),
    )
}

#[cfg(test)]
mod tests {
    use super::is_public_collaboration_tool;

    #[test]
    fn app_collaboration_agents_cannot_see_private_capabilities() {
        for denied in [
            "read_file",
            "list_dir",
            "local_shell",
            "shell",
            "memory_read",
            "memory_write",
            "configure_mcp",
        ] {
            assert!(!is_public_collaboration_tool(denied), "{denied}");
        }
        for allowed in ["web_search", "fetch", "list_skills", "read_skill"] {
            assert!(is_public_collaboration_tool(allowed), "{allowed}");
        }
    }
}
