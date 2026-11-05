# QuestionCrawler - Backend

Gradle multi-project, Java 21.

- `core/` - framework-free library + CLI (`com.questioncrawler.core`): source adapters, sync, normalization, classification,
  file storage, Lucene search. Main class `com.questioncrawler.core.cli.Main` (`installDist` produces `bin/questioncrawler`).
- `app/` - Spring Boot 3.3+ REST API (version set in `gradle.properties`) and optional scheduler over `core`.

```bash
gradle wrapper --gradle-version 8.14   # one-time
./gradlew :core:test :app:test
./gradlew :core:installDist            # CLI used by scripts/qc.sh and the Copilot skill
./gradlew :app:bootRun                 # http://127.0.0.1:8080
```

See ../docs/ARCHITECTURE.md.
