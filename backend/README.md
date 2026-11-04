# QuestionCrawler – Backend

Spring Boot 3.5 · Java 21 · Gradle (Groovy DSL). Standalone project: build it from this directory.

```bash
gradle wrapper --gradle-version 8.14   # one-time, generates gradlew + wrapper jar
./gradlew test
./gradlew bootRun                      # http://localhost:8080/api/health
```

Package layout (`com.questioncrawler.*`): `source`, `sync`, `normalize`,
`classification`, `storage`, `search`, `web`, `config`. Each maps to one responsibility
from the project requirements.
