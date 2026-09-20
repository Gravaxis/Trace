plugins {
  `kotlin-dsl`
}

// Precompiled script plugins cannot use type-safe `libs` accessors (gradle/gradle#15383), and the
// `plugins {}` block of one cannot take a version. Third-party plugins are therefore added here as
// plugin-marker dependencies and applied by id, without a version, inside the convention scripts.
fun pluginMarker(plugin: Provider<PluginDependency>): Provider<String> =
  plugin.map { "${it.pluginId}:${it.pluginId}.gradle.plugin:${it.version}" }

dependencies {
  implementation(pluginMarker(libs.plugins.shadow))
  implementation(pluginMarker(libs.plugins.spotless))
  implementation(pluginMarker(libs.plugins.errorprone))
  implementation(pluginMarker(libs.plugins.resource.factory.paper))
  implementation(pluginMarker(libs.plugins.run.paper))
  implementation(pluginMarker(libs.plugins.maven.publish))
  implementation(pluginMarker(libs.plugins.forbidden.apis))
}
