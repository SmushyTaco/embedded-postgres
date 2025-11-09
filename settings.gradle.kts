val name = providers.gradleProperty("name")
rootProject.name = name.get()
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    val dokkaVersion = providers.gradleProperty("dokka_version")
    val yumiGradleLicenserVersion = providers.gradleProperty("yumi_gradle_licenser_version")
    val dotenvVersion = providers.gradleProperty("dotenv_version")
    val nmcpVersion = providers.gradleProperty("nmcp_version")
    plugins {
        id("org.jetbrains.dokka").version(dokkaVersion.get())
        id("dev.yumi.gradle.licenser").version(yumiGradleLicenserVersion.get())
        id("co.uzzu.dotenv.gradle").version(dotenvVersion.get())
        id("com.gradleup.nmcp").version(nmcpVersion.get())
    }
}