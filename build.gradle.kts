plugins {
    `java-library`
    `maven-publish`
    signing
    id("org.jetbrains.dokka")
    id("dev.yumi.gradle.licenser")
    id("co.uzzu.dotenv.gradle")
    id("com.gradleup.nmcp")
}

val projectName = providers.gradleProperty("name")
val projectGroup = providers.gradleProperty("group")
val projectVersion = providers.gradleProperty("version")

val javaVersion = providers.gradleProperty("java_version")
val gradleJavaVersion = providers.gradleProperty("gradle_java_version")

val embeddedPostgresBinariesVersion = providers.gradleProperty("embedded_postgres_binaries_version")
val commonsCompressVersion = providers.gradleProperty("commons_compress_version")
val flywayVersion = providers.gradleProperty("flyway_version")
val junitVersion = providers.gradleProperty("junit_version")
val liquibaseVersion = providers.gradleProperty("liquibase_version")
val postgresqlVersion = providers.gradleProperty("postgresql_version")
val slf4jVersion = providers.gradleProperty("slf4j_version")
val xzVersion = providers.gradleProperty("xz_version")
val dokkaVersion = providers.gradleProperty("dokka_version")
val projectDescription = "Embedded PostgreSQL Server"

description = projectDescription
base.archivesName = projectName.get()
group = projectGroup.get()
version = projectVersion.get()

repositories { mavenCentral() }

dependencies {
    dokkaPlugin("org.jetbrains.dokka:kotlin-as-java-plugin:${dokkaVersion.get()}")

    runtimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-windows-amd64:${embeddedPostgresBinariesVersion.get()}")
    runtimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-darwin-amd64:${embeddedPostgresBinariesVersion.get()}")
    runtimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64:${embeddedPostgresBinariesVersion.get()}")
    runtimeOnly("io.zonky.test.postgres:embedded-postgres-binaries-linux-amd64-alpine:${embeddedPostgresBinariesVersion.get()}")

    implementation("org.slf4j:slf4j-api:${slf4jVersion.get()}")
    implementation("org.apache.commons:commons-compress:${commonsCompressVersion.get()}")
    implementation("org.tukaani:xz:${xzVersion.get()}")
    implementation("org.postgresql:postgresql:${postgresqlVersion.get()}")

    compileOnly("org.flywaydb:flyway-database-postgresql:${flywayVersion.get()}")
    compileOnly("org.liquibase:liquibase-core:${liquibaseVersion.get()}")
    compileOnly("org.junit.jupiter:junit-jupiter-api:${junitVersion.get()}")

    testImplementation("org.flywaydb:flyway-database-postgresql:${flywayVersion.get()}")
    testImplementation("org.liquibase:liquibase-core:${liquibaseVersion.get()}")
    testImplementation(platform("org.junit:junit-bom:${junitVersion.get()}"))
    testImplementation("org.junit.jupiter:junit-jupiter")

    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.slf4j:slf4j-simple:${slf4jVersion.get()}")
}
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(javaVersion.get().toInt())
        vendor = JvmVendorSpec.ADOPTIUM
    }
    sourceCompatibility = JavaVersion.toVersion(javaVersion.get().toInt())
    targetCompatibility = JavaVersion.toVersion(javaVersion.get().toInt())
    withSourcesJar()
    withJavadocJar()
}
tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        sourceCompatibility = javaVersion.get()
        targetCompatibility = javaVersion.get()
        if (javaVersion.get().toInt() > 8) options.release = javaVersion.get().toInt()
    }
    named<UpdateDaemonJvm>("updateDaemonJvm") {
        languageVersion = JavaLanguageVersion.of(gradleJavaVersion.get().toInt())
        vendor = JvmVendorSpec.ADOPTIUM
    }
    withType<JavaExec>().configureEach { defaultCharacterEncoding = "UTF-8" }
    withType<Javadoc>().configureEach { options.encoding = "UTF-8" }
    withType<Test>().configureEach {
        defaultCharacterEncoding = "UTF-8"
        useJUnitPlatform()
    }
    register<Jar>("dokkaJar") {
        group = JavaBasePlugin.DOCUMENTATION_GROUP
        dependsOn(dokkaGenerateHtml)
        archiveClassifier = "dokka"
        from(layout.buildDirectory.dir("dokka/html"))
    }
    named("build") { dependsOn(named("dokkaJar")) }
}
license {
    rule(file("./HEADER"))
    include("**/*.java")
    exclude("**/*.properties")
}
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = project.group.toString()
            artifactId = base.archivesName.get()
            version = project.version.toString()
            artifact(tasks.named("dokkaJar"))
            pom {
                name = projectName
                description = projectDescription
                url = "https://github.com/SmushyTaco/embedded-postgres"

                licenses {
                    license {
                        name = "The Apache License, Version 2.0"
                        url = "https://www.apache.org/licenses/LICENSE-2.0.txt"
                        distribution = "repo"
                    }
                }
                developers {
                    developer {
                        id = "smushytaco"
                        name = "Nikan Radan"
                        email = "personal@nikanradan.com"
                    }
                }
                scm {
                    url = "https://github.com/SmushyTaco/embedded-postgres"
                    connection = "scm:git:https://github.com/SmushyTaco/embedded-postgres.git"
                    developerConnection = "scm:git:https://github.com/SmushyTaco/embedded-postgres.git"
                }
            }
        }
    }
}
signing {
    val keyFile = layout.projectDirectory.file("./private-key.asc")
    if (keyFile.asFile.exists()) {
        isRequired = true
        useInMemoryPgpKeys(
            providers.fileContents(keyFile).asText.get(),
            env.fetch("PASSPHRASE", "")
        )
        sign(publishing.publications)
    }
}
nmcp {
    publishAllPublicationsToCentralPortal {
        username = env.fetch("USERNAME_TOKEN", "")
        password = env.fetch("PASSWORD_TOKEN", "")
        publishingType = "USER_MANAGED"
    }
}