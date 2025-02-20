/*
 * Copyright 2012-2021 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.build;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

import io.spring.javaformat.gradle.FormatTask;
import io.spring.javaformat.gradle.SpringJavaFormatPlugin;
import org.gradle.api.JavaVersion;
import org.gradle.api.Project;
import org.gradle.api.artifacts.Configuration;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.Dependency;
import org.gradle.api.artifacts.DependencySet;
import org.gradle.api.plugins.JavaBasePlugin;
import org.gradle.api.plugins.JavaPlugin;
import org.gradle.api.plugins.quality.CheckstyleExtension;
import org.gradle.api.plugins.quality.CheckstylePlugin;
import org.gradle.api.tasks.SourceSet;
import org.gradle.api.tasks.SourceSetContainer;
import org.gradle.api.tasks.bundling.Jar;
import org.gradle.api.tasks.compile.JavaCompile;
import org.gradle.api.tasks.javadoc.Javadoc;
import org.gradle.api.tasks.testing.Test;
import org.gradle.testretry.TestRetryPlugin;
import org.gradle.testretry.TestRetryTaskExtension;

import org.springframework.boot.build.optional.OptionalDependenciesPlugin;
import org.springframework.boot.build.testing.TestFailuresPlugin;
import org.springframework.boot.build.toolchain.ToolchainPlugin;

/**
 * Conventions that are applied in the presence of the {@link JavaBasePlugin}. When the
 * plugin is applied:
 *
 * <ul>
 * <li>{@code sourceCompatibility} is set to {@code 1.8}
 * <li>{@link SpringJavaFormatPlugin Spring Java Format}, {@link CheckstylePlugin
 * Checkstyle}, {@link TestFailuresPlugin Test Failures}, and {@link TestRetryPlugin Test
 * Retry} plugins are applied
 * <li>{@link Test} tasks are configured to use JUnit Platform and use a max heap of 1024M
 * <li>A {@code testRuntimeOnly} dependency upon
 * {@code org.junit.platform:junit-platform-launcher} is added to projects with the
 * {@link JavaPlugin} applied
 * <li>{@link JavaCompile}, {@link Javadoc}, and {@link FormatTask} tasks are configured
 * to use UTF-8 encoding
 * <li>{@link JavaCompile} tasks are configured to use {@code -parameters} and, when
 * compiling with Java 8, to:
 * <ul>
 * <li>Treat warnings as errors
 * <li>Enable {@code unchecked}, {@code deprecation}, {@code rawtypes}, and {@code varags}
 * warnings
 * </ul>
 * <li>{@link Jar} tasks are configured to produce jars with LICENSE.txt and NOTICE.txt
 * files and the following manifest entries:
 * <ul>
 * <li>{@code Automatic-Module-Name}
 * <li>{@code Build-Jdk-Spec}
 * <li>{@code Built-By}
 * <li>{@code Implementation-Title}
 * <li>{@code Implementation-Version}
 * </ul>
 * <li>{@code spring-boot-parent} is used for dependency management</li>
 * </ul>
 *
 * <p/>
 *
 * @author Andy Wilkinson
 * @author Christoph Dreis
 * @author Mike Smithson
 * @author Scott Frederick
 */
class JavaConventions {

	void apply(Project project) {
		project.getPlugins().withType(JavaBasePlugin.class, (java) -> {
			project.getPlugins().apply(TestFailuresPlugin.class);
			configureSpringJavaFormat(project);
			project.setProperty("sourceCompatibility", "1.8");
			configureJavaCompileConventions(project);
			configureJavadocConventions(project);
			configureTestConventions(project);
			configureJarManifestConventions(project);
			configureDependencyManagement(project);
			configureToolchain(project);
		});
	}

	private void configureJarManifestConventions(Project project) {
		ExtractResources extractLegalResources = project.getTasks().create("extractLegalResources",
				ExtractResources.class);
		extractLegalResources.getDestinationDirectory().set(project.getLayout().getBuildDirectory().dir("legal"));
		extractLegalResources.setResourcesNames(Arrays.asList("LICENSE.txt", "NOTICE.txt"));
		extractLegalResources.property("version", project.getVersion().toString());
		SourceSetContainer sourceSets = project.getExtensions().getByType(SourceSetContainer.class);
		Set<String> sourceJarTaskNames = sourceSets.stream().map(SourceSet::getSourcesJarTaskName)
				.collect(Collectors.toSet());
		Set<String> javadocJarTaskNames = sourceSets.stream().map(SourceSet::getJavadocJarTaskName)
				.collect(Collectors.toSet());
		project.getTasks().withType(Jar.class, (jar) -> project.afterEvaluate((evaluated) -> {
			jar.metaInf((metaInf) -> metaInf.from(extractLegalResources));
			jar.manifest((manifest) -> {
				Map<String, Object> attributes = new TreeMap<>();
				attributes.put("Automatic-Module-Name", project.getName().replace("-", "."));
				attributes.put("Build-Jdk-Spec", project.property("sourceCompatibility"));
				attributes.put("Built-By", "Spring");
				attributes.put("Implementation-Title",
						determineImplementationTitle(project, sourceJarTaskNames, javadocJarTaskNames, jar));
				attributes.put("Implementation-Version", project.getVersion());
				manifest.attributes(attributes);
			});
		}));
	}

	private String determineImplementationTitle(Project project, Set<String> sourceJarTaskNames,
			Set<String> javadocJarTaskNames, Jar jar) {
		if (sourceJarTaskNames.contains(jar.getName())) {
			return "Source for " + project.getName();
		}
		if (javadocJarTaskNames.contains(jar.getName())) {
			return "Javadoc for " + project.getName();
		}
		return project.getDescription();
	}

	private void configureTestConventions(Project project) {
		project.getTasks().withType(Test.class, (test) -> {
			test.useJUnitPlatform();
			test.setMaxHeapSize("1024M");
			if (buildingWithJava8(project)) {
				test.systemProperty("java.security.properties",
						getClass().getClassLoader().getResource("jdk-8156584-security.properties"));
			}
		});
		project.getPlugins().withType(JavaPlugin.class, (javaPlugin) -> project.getDependencies()
				.add(JavaPlugin.TEST_RUNTIME_ONLY_CONFIGURATION_NAME, "org.junit.platform:junit-platform-launcher"));
		project.getPlugins().apply(TestRetryPlugin.class);
		project.getTasks().withType(Test.class,
				(test) -> project.getPlugins().withType(TestRetryPlugin.class, (testRetryPlugin) -> {
					TestRetryTaskExtension testRetry = test.getExtensions().getByType(TestRetryTaskExtension.class);
					testRetry.getFailOnPassedAfterRetry().set(true);
					testRetry.getMaxRetries().set(isCi() ? 3 : 0);
				}));
	}

	private boolean isCi() {
		return Boolean.parseBoolean(System.getenv("CI"));
	}

	private void configureJavadocConventions(Project project) {
		project.getTasks().withType(Javadoc.class, (javadoc) -> javadoc.getOptions().source("1.8").encoding("UTF-8"));
	}

	private void configureJavaCompileConventions(Project project) {
		project.getTasks().withType(JavaCompile.class, (compile) -> {
			compile.getOptions().setEncoding("UTF-8");
			compile.setSourceCompatibility("1.8");
			compile.setTargetCompatibility("1.8");
			List<String> args = compile.getOptions().getCompilerArgs();
			if (!args.contains("-parameters")) {
				args.add("-parameters");
			}
			if (buildingWithJava8(project)) {
				args.addAll(Arrays.asList("-Werror", "-Xlint:unchecked", "-Xlint:deprecation", "-Xlint:rawtypes",
						"-Xlint:varargs"));
			}
		});
	}

	private boolean buildingWithJava8(Project project) {
		return !project.hasProperty("toolchainVersion") && JavaVersion.current() == JavaVersion.VERSION_1_8;
	}

	private void configureSpringJavaFormat(Project project) {
		project.getPlugins().apply(SpringJavaFormatPlugin.class);
		project.getTasks().withType(FormatTask.class, (formatTask) -> formatTask.setEncoding("UTF-8"));
		project.getPlugins().apply(CheckstylePlugin.class);
		CheckstyleExtension checkstyle = project.getExtensions().getByType(CheckstyleExtension.class);
		checkstyle.setToolVersion("8.29");
		checkstyle.getConfigDirectory().set(project.getRootProject().file("src/checkstyle"));
		String version = SpringJavaFormatPlugin.class.getPackage().getImplementationVersion();
		DependencySet checkstyleDependencies = project.getConfigurations().getByName("checkstyle").getDependencies();
		checkstyleDependencies
				.add(project.getDependencies().create("io.spring.javaformat:spring-javaformat-checkstyle:" + version));
	}

	private void configureDependencyManagement(Project project) {
		ConfigurationContainer configurations = project.getConfigurations();
		Configuration dependencyManagement = configurations.create("dependencyManagement", (configuration) -> {
			configuration.setVisible(false);
			configuration.setCanBeConsumed(false);
			configuration.setCanBeResolved(false);
		});
		configurations
				.matching((configuration) -> configuration.getName().endsWith("Classpath")
						|| JavaPlugin.ANNOTATION_PROCESSOR_CONFIGURATION_NAME.equals(configuration.getName()))
				.all((configuration) -> configuration.extendsFrom(dependencyManagement));
		Dependency springBootParent = project.getDependencies().enforcedPlatform(project.getDependencies()
				.project(Collections.singletonMap("path", ":spring-boot-project:spring-boot-parent")));
		dependencyManagement.getDependencies().add(springBootParent);
		project.getPlugins().withType(OptionalDependenciesPlugin.class, (optionalDependencies) -> configurations
				.getByName(OptionalDependenciesPlugin.OPTIONAL_CONFIGURATION_NAME).extendsFrom(dependencyManagement));
	}

	private void configureToolchain(Project project) {
		project.getPlugins().apply(ToolchainPlugin.class);
	}

}
