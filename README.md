# SmartGallery

An offline-capable AI image search application. Uses CLIP semantic embeddings (via ONNX Runtime) to let you search your photo library with natural language — "sunset over mountains", "birthday party", "my cat sleeping".

## Quick Start

### Requirements
- Java 17+
- Maven 3.8+
- Windows / macOS / Linux
- Internet access **once** (to download models)

### Build
```powershell
cd SmartGallery
mvn clean package -DskipTests
```

### Run
```powershell
# Run as Administrator if using port 80 (required on Windows)
java -jar target/SmartGallery-0.0.1-SNAPSHOT.jar
```

Open your browser: **http://localhost**

> **Note for port 80 on Windows**: Right-click Command Prompt → "Run as administrator", then run the JAR.  
> Or change `server.port=80` to `server.port=8080` in `src/main/resources/application.properties` to avoid this.

---

## First-Run Setup

1. **Open Settings** (gear icon in the top-right)
2. **Enter your Hugging Face token** (see [Getting a Token](#getting-a-hugging-face-token))
3. Click **Save Token** → then **Download Models**
4. Watch the progress bar — download is ~600 MB (vision_model.onnx + text_model.onnx + tokenizer.json)
5. When status shows **Models Ready**, you're set!

---

## Getting a Hugging Face Token

1. Go to [huggingface.co](https://huggingface.co) and create a free account
2. Go to **Settings → Access Tokens**
3. Click **New Token** → Type: *Read* → Copy the token (starts with `hf_`)
4. Paste into SmartGallery's Settings page

The token is **encrypted with AES-256-GCM** and stored locally. It is never transmitted except to Hugging Face for the one-time model download.

---

## Adding Images

**Option A — Add to default folder:**
```
./data/images/
```
Place any `.jpg`, `.jpeg`, `.png`, `.gif`, `.bmp`, `.webp`, or `.tiff` files here.

**Option B — Watch any folder:**
- Open Settings → **Folders** tab
- Enter the full path to your photo library (e.g. `C:\Users\me\Pictures`)
- Click **Add Folder**

After adding images, click the **refresh** (↺) button or go to Settings → **Reindex Now**.

Images are also **auto-indexed** when new files are detected in watched folders.

---

## Configuration (`application.properties`)

```properties
server.port=80

# Comma-separated list of image folders to index
app.image-dirs=./data/images,C:/Users/me/Pictures

# Where ONNX model files are stored
app.model-dir=./data/models

# Thumbnail directory (app generates all thumbnails itself)
app.thumb-dir=./data/thumbs

# Hugging Face CLIP ONNX model repository
app.hf-repo=Xenova/clip-vit-base-patch32
```

---

## Search Tips

| Query | What it finds |
|-------|--------------|
| `sunset mountains` | Landscape photos with warm light |
| `birthday party children` | Celebration shots with kids |
| `black cat sleeping` | Cat photos in resting poses |
| `beach vacation summer` | Holiday/beach imagery |
| `professional headshot` | Portrait-style photos |

Semantic search uses CLIP — it understands **concepts**, not just filenames!

For **visual search**: click the 📷 icon next to the search bar (or drag an image onto the page) to find visually similar images.

---

## API Reference

```bash
# Text search
curl -X POST http://localhost/api/search \
  -H "Content-Type: application/json" \
  -d '{"query":"sunset mountains","limit":20}'

# Image-to-image search
curl -X POST http://localhost/api/search/image \
  -F "file=@/path/to/query.jpg"

# Tag search
curl "http://localhost/api/search/tags?tag=vacation"

# Trigger full reindex
curl -X POST http://localhost/api/index/reindex

# Check index status
curl http://localhost/api/index/status

# Model status
curl http://localhost/api/models/status

# Download models
curl -X POST http://localhost/api/models/download -H "Content-Type: application/json" -d '{}'

# List watched folders
curl http://localhost/api/settings/folders

# Add folder
curl -X POST http://localhost/api/settings/folders \
  -H "Content-Type: application/json" \
  -d '{"folderPath":"C:/Users/me/Pictures"}'

# Update image tags
curl -X PATCH http://localhost/api/images/42/tags \
  -H "Content-Type: application/json" \
  -d '{"tags":["landscape","vacation"]}'
```

H2 Console (for debugging): **http://localhost/h2-console**  
JDBC URL: `jdbc:h2:file:./data/db/smartgallery`

---

## Troubleshooting

### App won't start on port 80
Run as Administrator, or change `server.port` in `application.properties` to `8080`.

### "Models Not Downloaded" badge
Go to Settings, enter your HF token, and click Download Models.

### Download fails with 401
Your Hugging Face token is invalid or expired. Go to HF → Settings → Access Tokens to generate a new one.

### Thumbnails not appearing
The app generates thumbnails automatically using Java (Thumbnailator). Check that `./data/thumbs/` is writable.  
Delete `./data/thumbs/` and reindex to regenerate all thumbnails.

### Search returns no results after adding images
1. Click the ↺ (reindex) button or go to Settings → Reindex Now
2. Wait for the "Indexing..." pill to disappear
3. Ensure CLIP models are loaded (green "Models Ready" badge)

### `OutOfMemoryError` on large libraries
Increase heap: `java -Xmx4g -jar target/SmartGallery-0.0.1-SNAPSHOT.jar`  
At 100k images, embeddings use ~200 MB of heap.

### ONNX model shape mismatch
The app targets `Xenova/clip-vit-base-patch32`. If you switch to a different model, you may need to adjust input/output tensor names in `OnnxInferenceService.java`.

---

## Model Files Expected

| File | Size | Description |
|------|------|-------------|
| `data/models/vision_model.onnx` | ~330 MB | CLIP image encoder |
| `data/models/text_model.onnx` | ~250 MB | CLIP text encoder |
| `data/models/tokenizer.json` | ~600 KB | BPE vocabulary + merge rules |

---

## Demo Script

```powershell
# 1. Start the app
java -jar target/SmartGallery-0.0.1-SNAPSHOT.jar

# 2. Open browser, go to Settings, enter HF token → Save → Download Models
# 3. Copy some images
Copy-Item "C:\Users\me\Pictures\*.jpg" ".\data\images\"

# 4. Reindex
curl -X POST http://localhost/api/index/reindex

# 5. Search
curl -X POST http://localhost/api/search `
  -H "Content-Type: application/json" `
  -d '{"query":"dog playing in park","limit":10}'
```
