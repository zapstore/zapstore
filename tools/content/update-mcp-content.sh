#!/bin/bash
set -e

# Get the directory where this script is located
SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"

echo "Generating MCP content..."
cd "$SCRIPT_DIR/../../../../purplebase/purplestack-context"
./generate-content.sh

echo "Copying MCP content to project..."
cp "$SCRIPT_DIR/../../../../purplebase/purplestack/tools/content/mcp-content.zip" "$SCRIPT_DIR/mcp-content.zip"

echo "MCP content updated successfully!"

