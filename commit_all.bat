git reset
git add "src/main/java/com/smartgallery/controller/OcrController.java"
git commit -m "Add REST endpoints for downloading, toggling, and managing custom OCR language models"

git add "src/main/java/com/smartgallery/service/OcrModelService.java"
git commit -m "Implement background service for parsing, downloading, and verifying custom ONNX language models"

git add "src/main/java/com/smartgallery/service/OcrService.java"
git commit -m "Refactor OCR inference engine to dynamically inject custom language models and avoid native crashes"

git add "src/main/java/com/smartgallery/service/SearchService.java"
git commit -m "Improve text search accuracy for non-ASCII queries like Tamil by bypassing case-folding"

git add "src/main/java/com/smartgallery/repository/ImageRepository.java"
git commit -m "Add raw text extraction queries to accurately match complex language characters in database searches"

git add "src/main/java/com/smartgallery/dto/SearchResultItem.java"
git commit -m "Update search results data transfer object to correctly expose OCR text matches to the interface"

git add "src/main/java/com/smartgallery/service/ImageIndexerService.java"
git commit -m "Fix file indexing to ensure images with missing or garbled OCR text are correctly re-scanned"

git add "src/main/java/com/smartgallery/entity/ImageEntity.java"
git commit -m "Adjust internal image representations to better support extracted text indexing processes"

git add "src/main/java/com/smartgallery/controller/SettingsController.java"
git commit -m "Clean up outdated dependencies and unused endpoint configurations"

git add "src/main/java/com/smartgallery/service/SettingsService.java"
git commit -m "Enable dynamic tracking and retrieval of active OCR language selections"

git add "src/main/resources/application.properties"
git commit -m "Configure base paths and system toggles for the new multilingual OCR execution"

git add "src/main/resources/static/app.js"
git commit -m "Integrate networking hooks for the frontend to request model downloads and update language settings"

git add "src/main/resources/static/index.html"
git commit -m "Add configuration modal menus for user selection of OCR models and hardware preference"

git add "pom.xml"
git commit -m "Update project dependencies to support the latest core runtime needs and metadata handling"

git add ".gitignore"
git commit -m "Exclude JVM crash dumps, temporary OCR folders, and IDE caches from version control"

git add "src/test/"
git commit -m "Add new test suites to validate custom OCR endpoint and configuration behaviors"
