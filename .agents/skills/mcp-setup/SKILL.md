---
name: mcp-setup
description: Spawns and configures Model Context Protocol (MCP) integrations for ShizukuPlus.
license: Complete terms in LICENSE
metadata:
  author: Google LLC
  keywords:
  - mcp
  - setup
  - integration
---
# MCP Configuration Setup Skill

This skill ensures standard MCP servers are available for context augmentation.

## Execution Steps:

1. **Initialize Global Config:**
   Antigravity uses a standard `mcp.json` or runs them directly via `npx`.
   To immediately spawn the Google Developer documentation or GitHub MCP inside your shell context during a task, invoke them via the `run_command` tool in the background:
   
   ```bash
   npx -y @modelcontextprotocol/server-github
   ```

2. **Persistent Setup:**
   If the user has a central configuration file (e.g. Claude Desktop or similar client), append the following:
   ```json
   {
     "mcpServers": {
       "github": {
         "command": "npx",
         "args": ["-y", "@modelcontextprotocol/server-github"]
       },
       "google-maps": {
         "command": "npx",
         "args": ["-y", "@modelcontextprotocol/server-google-maps"]
       }
     }
   }
   ```
