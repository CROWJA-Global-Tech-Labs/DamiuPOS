# claude-proxy Dockerfile
# - Node 22 alpine for small image
# - Installs Claude Code CLI globally so the SDK can find it on PATH
# - HOME=/home/node so .claude/ credentials sit in a mountable volume
# - Runs as `node` user (uid 1000) for slightly less privilege

FROM node:22-alpine

# Tools needed at runtime by Claude Code (git for repo ops; curl optional)
RUN apk add --no-cache git ca-certificates

# Install Claude Code CLI globally — the Agent SDK shells out to this binary
RUN npm install -g @anthropic-ai/claude-code

WORKDIR /app

# Install proxy deps
COPY package.json ./
RUN npm install --omit=dev && npm cache clean --force

COPY server.js ./

# Use built-in `node` user; ensure HOME points at a writable, mountable dir
USER node
ENV HOME=/home/node

EXPOSE 3000
CMD ["node", "server.js"]
