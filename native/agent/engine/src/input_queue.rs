use std::collections::VecDeque;
use std::sync::Mutex;

/// A queued user contribution. `mind_context` is optional private state that
/// must be injected as an internal/system message, never as user text.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct QueuedInput {
    pub text: String,
    pub mind_context: Option<String>,
}

#[derive(Default)]
pub struct InputQueue {
    messages: Mutex<VecDeque<QueuedInput>>,
}

impl InputQueue {
    pub fn push(&self, message: String) {
        self.push_with_mind_context(message, None);
    }

    pub fn push_with_mind_context(&self, message: String, mind_context: Option<String>) {
        self.messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .push_back(QueuedInput {
                text: message,
                mind_context,
            });
    }


    pub fn drain(&self) -> Vec<String> {
        self.drain_items().into_iter().map(|queued| queued.text).collect()
    }

    pub fn drain_items(&self) -> Vec<QueuedInput> {
        self.messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .drain(..)
            .collect()
    }


    pub fn discard_front(&self, message: &str) {
        let mut messages = self
            .messages
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if messages.front().is_some_and(|queued| queued.text == message) {
            messages.pop_front();
        }
    }
}
