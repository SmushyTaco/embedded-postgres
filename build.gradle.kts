plugins {
    `java-library`
    `maven-publish`
    signing
    alias(libs.plugins.dokka)
    alias(libs.plugins.yumiGradleLicenser)
    alias(libs.plugins.dotenv)
    alias(libs.plugins.nmcp)
}

val projectName = providers.gradleProperty("name")
val projectGroup = providers.gradleProperty("group")
val projectVersion = providers.gradleProperty("version")
val projectDescription = providers.gradleProperty("description")

val publishingUrl = providers.gradleProperty("url")

val licenseName = providers.gradleProperty("license_name")
val licenseUrl = providers.gradleProperty("license_url")
val licenseDistribution = providers.gradleProperty("license_distribution")

val developerId = providers.gradleProperty("developer_id")
val developerName = providers.gradleProperty("developer_name")
val developerEmail = providers.gradleProperty("developer_email")

val publishingStrategy = providers.gradleProperty("publishing_strategy")

val javaVersion = libs.versions.java.map { it.toInt() }

base.archivesName = projectName
group = projectGroup.get()
version = projectVersion.get()
description = projectDescription.get()

repositories { mavenCentral() }

dependencies {
    dokkaPlugin(libs.dokkaJavaPlugin)

    runtimeOnly(libs.embeddedPostgresBinaries.windows)
    runtimeOnly(libs.embeddedPostgresBinaries.darwin)
    runtimeOnly(libs.embeddedPostgresBinaries.linux)
    runtimeOnly(libs.embeddedPostgresBinaries.alpineLinux)

    implementation(libs.slf4j.api)
    implementation(libs.commonsCompress)
    implementation(libs.xz)
    implementation(libs.postgresql)

    compileOnly(libs.flyway)
    compileOnly(libs.liquibase)
    compileOnly(libs.junit.jupiterApi)

    testImplementation(libs.flyway)
    testImplementation(libs.liquibase)
    testImplementation(libs.junit.jupiter)

    testRuntimeOnly(libs.junit.platformLauncher)
    testRuntimeOnly(libs.slf4j.simple)
}
java {
    toolchain {
        languageVersion = javaVersion.map { JavaLanguageVersion.of(it) }
        vendor = JvmVendorSpec.ADOPTIUM
    }
    sourceCompatibility = JavaVersion.toVersion(javaVersion.get())
    targetCompatibility = JavaVersion.toVersion(javaVersion.get())
    withSourcesJar()
    withJavadocJar()
}
tasks {
    withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        sourceCompatibility = javaVersion.get().toString()
        targetCompatibility = javaVersion.get().toString()
        if (javaVersion.get() > 8) options.release = javaVersion
    }
    named<UpdateDaemonJvm>("updateDaemonJvm") {
        languageVersion = libs.versions.gradleJava.map { JavaLanguageVersion.of(it.toInt()) }
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
            groupId = projectGroup.get()
            artifactId = projectName.get()
            version = projectVersion.get()
            artifact(tasks.named("dokkaJar"))
            pom {
                name = projectName
                description = projectDescription
                url = publishingUrl

                licenses {
                    license {
                        name = licenseName
                        url = licenseUrl
                        distribution = licenseDistribution
                    }
                }
                developers {
                    developer {
                        id = developerId
                        name = developerName
                        email = developerEmail
                    }
                }
                scm {
                    url = publishingUrl
                    connection = publishingUrl.map { "scm:git:$it.git" }
                    developerConnection = publishingUrl.map { "scm:git:$it.git" }
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
        publishingType = publishingStrategy
    }
}