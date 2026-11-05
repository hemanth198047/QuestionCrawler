# Using your organization's Gradle

QuestionCrawler's backend is built with Gradle. **You do not need the Gradle wrapper or a separate download**: `npm start` uses the
Gradle your organization already provides.

## 1. Tell the launcher where Gradle is

The launcher looks, in this order, at: the `QC_GRADLE` setting, a wrapper in `backend/`, `gradle` on your PATH, and `GRADLE_HOME`.
It needs **Gradle 8.5 or newer** (Java 21 requires it). Check yours with `gradle -v`.

| Situation | What to do |
|---|---|
| Gradle is on your PATH | Nothing. Open a new terminal and run `npm start`. |
| You know where it is | PowerShell: `$env:QC_GRADLE="C:\tools\gradle-8.14\bin\gradle.bat"; npm start`<br>Mac/Linux: `QC_GRADLE=/opt/gradle-8.14/bin/gradle npm start` |
| Your organization installs it through a tool (a software portal, `scoop`, `choco`, a module, a Docker image...) | Install it that way, then follow one of the rows above. |

Paths with spaces are fine.

## 2. If dependencies cannot be downloaded

Many companies block `repo.maven.apache.org` and `plugins.gradle.org` and offer an internal mirror (Artifactory, Nexus). The
first build then fails while resolving the Spring Boot plugin or libraries. Fix it **outside the repository** with a Gradle init script:

1. Ask your platform team for the internal URL of the Maven Central mirror and of the Gradle plugin mirror.
2. Copy [`backend/org-mirror.init.gradle.example`](../backend/org-mirror.init.gradle.example) to
   `%USERPROFILE%\.gradle\init.d\org-mirror.gradle` (Mac/Linux: `~/.gradle/init.d/`), and replace the two URLs.
3. If the mirror needs credentials, set `ORG_REPO_USER` and `ORG_REPO_TOKEN` as environment variables (never in the file).
4. Run `npm start` again.

To apply a script only for one run without copying it: `$env:QC_GRADLE_ARGS="--init-script C:\path\org-mirror.gradle"; npm start`.

The example is a widely used pattern but could not be tested against your repository. If it does not work, send me the first
error line from the `api │` output and what your platform team says the repository URLs and authentication are.

## 3. Some other places that may need a mirror

- **npm** (the frontend): `npm config set registry https://your-internal-npm-registry/` (ask your platform team).
- **Gradle's own distribution**: not needed when you use your organization's installed Gradle. Only a wrapper downloads one.

## 4. Using a wrapper anyway

If your organization prefers a wrapper, run `gradle wrapper --gradle-version 8.14` in `backend/`, and change `distributionUrl` in
`backend/gradle/wrapper/gradle-wrapper.properties` to your internal mirror of the Gradle distribution. The launcher will then use it.
