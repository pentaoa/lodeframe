# Lodeframe

**Forge every frame.**

[![Build](https://github.com/pentaoa/lodeframe/actions/workflows/build.yml/badge.svg)](https://github.com/pentaoa/lodeframe/actions/workflows/build.yml)

Lodeframe is an experimental native Metal rendering backend for Minecraft: Java Edition on Apple Silicon. Its goal is to provide a correct, stable, and efficient macOS renderer without routing Minecraft through OpenGL or Vulkan.

Lodeframe is currently pre-alpha. It is not yet a drop-in replacement for the standard renderer, and performance and mod compatibility have not been established.

## Current scope

- Native rendering through Apple's Metal API
- Minecraft 26.2 rendering-backend integration
- Sodium compatibility
- Renderer correctness, lifecycle stability, and reproducible benchmarks
- A future shader-pack compatibility layer in [Lodeframe Shaders](https://github.com/pentaoa/lodeframe-shaders)

## Requirements

- macOS
- Apple Silicon (M1 or newer)

## Origin

Lodeframe is an independent fork of [Metallum](https://github.com/kokodio/metallum). The existing Metal backend gives the project a practical starting point; development, product direction, releases, and support are managed independently here. Original copyright and MIT license terms are preserved.

Minecraft is a trademark of Microsoft. Lodeframe is not affiliated with Mojang Studios, Microsoft, or Apple.
