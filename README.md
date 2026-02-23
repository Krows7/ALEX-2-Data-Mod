# Research Data Mod (Minecraft Forge 1.16.5)

`data_mod` is a Minecraft client-side Forge mod for collecting gameplay observations and streaming them to an external TCP server.

The repository includes two parts:
- A Forge mod (`src/main/java/...`) that captures player state + frame snapshots and sends packets.
- A Python receiver (`server.py`) that accepts packets, stores frames as WebP, and writes metadata to JSONL.

## Features

- Captures gameplay observations on client ticks.
- Sends structured data over TCP with a custom binary protocol.
- Includes lightweight handshake (`HSK1` -> `OKAY`) before streaming.
- Supports SSH local port-forwarding from the in-game config screen.
- Stores received frames and metadata in run-based output directories.
- Preserves tick ordering on the server side (with configurable reorder tolerance).

## Tech Stack

- Minecraft `1.16.5`
- Forge `36.2.42`
- Java `8`
- Gradle (ForgeGradle)
- Python `3` + `numpy` + `Pillow`

## Repository Layout

- `src/main/java/net/krows_team/data_mod/DataMod.java`: mod entry point.
- `src/main/java/net/krows_team/data_mod/DataModConfigScreen.java`: in-game config UI.
- `src/main/java/net/krows_team/data_mod/ClientStreamManager.java`: capture and packet scheduling.
- `src/main/java/net/krows_team/data_mod/StreamSender.java`: TCP sender thread.
- `src/main/java/net/krows_team/data_mod/SshTunnelManager.java`: SSH tunnel process manager.
- `src/main/java/net/krows_team/data_mod/ClientConfig.java`: Forge client config values.
- `server.py`: TCP data receiver and disk writer.

## Quick Start

### 1) Start the Python receiver

Install dependencies:

```bash
pip install numpy pillow
```

Run server on default port `5000`:

```bash
python server.py --host 0.0.0.0 --port 5000
```

Default outputs:
- Frames: `frames/run_<id>/*.webp`
- Metadata: `data/run_<id>/data.jsonl`

### 2) Run the mod in development

```bash
./gradlew runClient
```

### 3) Configure connection in-game

Open the mod config screen and set:
- Server address (`host:port`), for example `127.0.0.1:5000`
- Processing threads
- Flush every N packets
- Optional SSH tunnel settings:
  - SSH host:port
  - SSH user
  - SSH private key path

Press **Connect**.

## Building the Mod JAR

```bash
./gradlew build
```

Reobfuscated mod JAR is generated in `build/libs/`.

## Data Protocol (High Level)

Transport framing:
- `int32 packet_len` (big-endian)
- `packet_len` bytes payload

Handshake payload (optional, 8 bytes):
- `uint32 MAGIC_HSK1` (`"HSK1"`)
- `uint32 protocol_version` (currently `1`)

Handshake response payload:
- `uint32 MAGIC_OKAY` (`"OKAY"`)
- `uint32 protocol_version`

Observation payload starts with:
- `uint32 MAGIC_MRL1` (`"MRL1"`)
- Tick sequence id
- Player stats (HP, armor, hunger, air, selected slot)
- Dimension / biome info
- Inventory, armor, offhand item IDs
- Image (`width`, `height`, raw BGR bytes)

## Server Output Format

Each line in `data.jsonl` is one observation record with fields like:
- `ts_server`, `tick`, `day_time`
- `hp`, `hp_max`, `armor`, `hunger`, `air`, `selected_slot`
- `dimension`, `biome`, `biome_category`
- `main_hand`, `inventory`, `armor_items`, `offhand`
- `image_w`, `image_h`
- `frame_path`

Frames are stored as lossless WebP files named by tick sequence.

## Useful `server.py` Options

- `--workers`: number of image encoding worker threads.
- `--job-queue-max`: max queued jobs from socket thread.
- `--res-queue-max`: max queued processed results.
- `--flush-every`: flush JSONL every N records (`0` = no periodic flush).
- `--webp-method`: WebP encoder method (`0..6`).
- `--max-reorder-gap`: when to skip missing sequence numbers.
- `--run-id`: force output run id.

Example:

```bash
python server.py \
  --host 0.0.0.0 \
  --port 5000 \
  --workers 4 \
  --job-queue-max 2000 \
  --res-queue-max 2000 \
  --webp-method 6 \
  --max-reorder-gap 512
```

## Notes

- The mod is client-side and intended for research data collection.
- The Python server currently accepts one active client connection at a time (`listen(1)`).
- If SSH tunnel mode is enabled, the mod forwards through local `127.0.0.1:<localPort>`.
