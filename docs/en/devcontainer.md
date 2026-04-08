# Dev Containers

## What are Dev Containers?

Dev Containers (Development Containers) are Docker-based development environments defined by a `devcontainer.json` configuration file. They ensure every developer on the team uses the same tools, runtimes, and settings, eliminating "works on my machine" problems.

## How They Work

1. A `devcontainer.json` file defines the environment (base image, tools, extensions)
2. Your IDE (VS Code, JetBrains, GitHub Codespaces) reads this file
3. A Docker container is created with the specified configuration
4. Your project code is mounted into the container
5. You develop inside the container with all tools pre-installed

## Key Concepts

### Base Image

The starting point for your container. Microsoft provides official Dev Container images for common languages (`mcr.microsoft.com/devcontainers/...`), or you can use any Docker image.

### Features

Dev Container Features are modular packages that add tools to your container without writing a Dockerfile. They are specified in the `features` section of `devcontainer.json`.

Browse available features: https://containers.dev/features

### Lifecycle Commands

Commands that run at different stages of the container lifecycle:

- `postCreateCommand`: Runs once after the container is created (e.g., install dependencies)
- `postStartCommand`: Runs every time the container starts
- `postAttachCommand`: Runs every time a client attaches to the container

### Port Forwarding

Dev Containers can forward ports from the container to your host machine. Declare ports in `forwardPorts` so services running inside the container are accessible from your browser or other tools.

### IDE Extensions

Extensions can be pre-installed inside the container via the `customizations` section. This ensures the entire team uses the same editor tooling.

## IDE Support

| IDE | Support |
|-----|---------|
| VS Code | Native via Dev Containers extension |
| GitHub Codespaces | Native (cloud-hosted Dev Containers) |
| JetBrains IDEs | Via Gateway / remote development |
| Cursor | Native (shares VS Code extension ecosystem) |

## This Template's Approach

The `.devcontainer/devcontainer.json` in this template is a **commented template**. It does not specify a base image or features because the base template is framework-agnostic. Derived repositories uncomment and configure the sections that match their stack.

## Tips

- **Keep images small**: Use language-specific images instead of installing everything via features
- **Cache dependencies**: Use `postCreateCommand` to install dependencies so they persist in the container
- **Port forwarding**: Declare `forwardPorts` for services your app runs (web server, database, etc.)
- **Environment variables**: Use `remoteEnv` for development-only variables, never for secrets

## References

Primary sources for the technologies described in this document:

- [Dev Containers Specification](https://containers.dev/) — Official specification, JSON reference, and feature registry
- [devcontainer.json Reference](https://containers.dev/implementors/json_reference/) — All configuration properties
- [Available Features](https://containers.dev/features) — Browse and search installable features
- [VS Code Dev Containers](https://code.visualstudio.com/docs/devcontainers/containers) — VS Code integration guide
- [GitHub Codespaces](https://docs.github.com/en/codespaces) — Cloud-hosted Dev Containers
- [Microsoft Dev Container Images](https://mcr.microsoft.com/en-us/catalog?search=devcontainers) — Official base images catalog
