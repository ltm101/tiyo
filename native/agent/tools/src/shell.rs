#[cfg(not(windows))]
use std::path::Path;
#[cfg(not(windows))]
use std::path::PathBuf;
use tokio::process::Command;

/// Build the shell command used by both `shell` and `local_shell`.
///
/// Android apps cannot execute binaries unpacked under their writable data directory on
/// current SELinux policies. Always use the trusted system shell there, even when an old
/// runtime exports TIYO_SHELL to an app-private Termux bash.
#[cfg(windows)]
pub(crate) fn platform_shell(command: &str) -> Command {
    let mut process = Command::new("powershell.exe");
    process.args(["-NoLogo", "-NoProfile", "-Command", command]);
    process
}

#[cfg(not(windows))]
pub(crate) fn platform_shell(command: &str) -> Command {
    let shell = shell_program();
    let mut process = Command::new(&shell);
    process.args([shell_flag(&shell), command]);
    process
}

#[cfg(target_os = "android")]
fn shell_program() -> PathBuf {
    PathBuf::from("/system/bin/sh")
}

#[cfg(all(unix, not(target_os = "android")))]
fn shell_program() -> PathBuf {
    std::env::var_os("TIYO_SHELL")
        .map(PathBuf::from)
        .or_else(|| {
            std::env::var_os("PREFIX")
                .map(PathBuf::from)
                .map(|prefix| prefix.join("bin").join("bash"))
        })
        .filter(|path| path.is_file())
        .unwrap_or_else(|| PathBuf::from("/bin/bash"))
}

#[cfg(not(windows))]
fn shell_flag(shell: &Path) -> &'static str {
    if shell
        .file_name()
        .and_then(|name| name.to_str())
        .is_some_and(|name| name.starts_with("bash"))
    {
        "-lc"
    } else {
        "-c"
    }
}

#[cfg(all(test, not(windows)))]
mod tests {
    use super::*;

    #[cfg(not(windows))]
    #[test]
    fn bash_uses_login_mode_but_system_sh_does_not() {
        assert_eq!(shell_flag(Path::new("/bin/bash")), "-lc");
        assert_eq!(shell_flag(Path::new("/system/bin/sh")), "-c");
    }

    #[cfg(target_os = "android")]
    #[test]
    fn android_ignores_app_private_shell_environment() {
        assert_eq!(shell_program(), PathBuf::from("/system/bin/sh"));
    }
}
