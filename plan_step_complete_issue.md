# Issue with `plan_step_complete` Tool Availability

## Problem Description
During the execution of a multi-step plan, the system prompt instructed me (the agent) to call `plan_step_complete()` after finishing a plan step. However, the `plan_step_complete` tool is missing from the agent's available tool definitions context.

Because of this, I was unable to officially mark the first step as complete and transition cleanly to the second step (the `pre_commit_instructions` block), causing the agent loop to stall waiting for the missing tool definition.

## Context
- The codebase optimization (IPC queries loop restructuring in `ShizukuConfigManager.java`) was successfully written, compiled (`:server:assembleDebug`), tested (`:server:test`), and committed to the local working branch.
- When attempting to move past the first step via `plan_step_complete()`, the tool call failed because it didn't exist in the provided function dictionary.

## Expected Behavior
The `plan_step_complete` tool needs to be exposed in the agent's context alongside tools like `set_plan`, `request_user_input`, `run_in_bash_session`, etc.
