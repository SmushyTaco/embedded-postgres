val name = providers.gradleProperty("name")
rootProject.name = name.get()
pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    val foojayResolverVersion = providers.gradleProperty("foojay_resolver_version")
    val dokkaVersion = providers.gradleProperty("dokka_version")
    val yumiGradleLicenserVersion = providers.gradleProperty("yumi_gradle_licenser_version")
    val dotenvVersion = providers.gradleProperty("dotenv_version")
    val nmcpVersion = providers.gradleProperty("nmcp_version")
    plugins {
        id("org.gradle.toolchains.foojay-resolver-convention").version(foojayResolverVersion.get())
        id("org.jetbrains.dokka").version(dokkaVersion.get())
        id("dev.yumi.gradle.licenser").version(yumiGradleLicenserVersion.get())
        id("co.uzzu.dotenv.gradle").version(dotenvVersion.get())
        id("com.gradleup.nmcp").version(nmcpVersion.get())
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention")
}