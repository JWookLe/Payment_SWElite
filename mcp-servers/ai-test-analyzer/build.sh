#!/bin/bash

set -e

echo "Building AI Test Analyzer MCP Server..."

# Install dependencies
npm install

# Build TypeScript
npm run build

echo "Build complete! Run with:"
echo "  export ANTHROPIC_API_KEY=your_api_key"
echo "  npm start"
