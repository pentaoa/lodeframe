# Lodeframe

**Forge every frame.**

[![Build](https://github.com/pentaoa/lodeframe/actions/workflows/build.yml/badge.svg)](https://github.com/pentaoa/lodeframe/actions/workflows/build.yml)

Lodeframe is an experimental native Metal rendering backend for Minecraft: Java Edition on Apple Silicon. Its goal is to provide a correct, stable, and efficient macOS renderer without routing Minecraft through OpenGL or Vulkan.

Lodeframe is currently pre-alpha. It is not yet a drop-in replacement for the standard renderer, and performance and mod compatibility have not been established.

## Current scope

- Native rendering through Apple's Metal API
- Minecraft 26.2 rendering-backend integration
- Sodium compatibility
- A Sodium settings entry for enabling and selecting local shader packs
- An in-development OptiFine/Iris-format renderer covering world gbuffers, directional shadows, composite passes, and final output through Metal
- Renderer correctness, lifecycle stability, and reproducible benchmarks
- The pack parser and legacy GLSL compatibility layer in [Lodeframe Shaders](https://github.com/pentaoa/lodeframe-shaders)

BSL 10.1.3 is the current conformance target. Its selected terrain, water, sky, entity, hand, particle, weather, shadow, composite, and final programs pass the offline GLSL frontend suite; rendered-output validation remains pre-alpha work.

## Requirements

- macOS
- Apple Silicon (M1 or newer)

## Origin

Lodeframe is an independent fork of [Metallum](https://github.com/kokodio/metallum). The existing Metal backend gives the project a practical starting point; development, product direction, releases, and support are managed independently here. Original copyright and MIT license terms are preserved.

Minecraft is a trademark of Microsoft. Lodeframe is not affiliated with Mojang Studios, Microsoft, or Apple.
