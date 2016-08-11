package org.java2typescript.maven.plugin;

/*
 * Copyright 2001-2005 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Collection;

import javax.ws.rs.Path;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import com.google.common.collect.Lists;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import java2typescript.jackson.module.Configuration;
import java2typescript.jackson.module.grammar.Module;
import java2typescript.jaxrs.ServiceDescriptorGenerator;

/**
 * Generate typescript file out of RESt service definition
 * 
 * @goal generate
 * @phase process-classes
 * @configurator include-project-dependencies
 * @requiresDependencyResolution compile+runtime
 */
public class MainMojo extends AbstractMojo {

	/**
	 * Full class name of the REST service
	 * @parameter
	 *    alias="serviceClass" 
	 *    expression="${j2ts.serviceClass}"
	 */
	private String restServiceClassName;

	/**
	 * Package name to scan for classes
	 * @parameter
	 *    alias="servicePackage"
	 *    expression="${j2ts.servicePackage}"
	 */
	private String restServicePackageName;

	/**
	 * Name of output module (ts,js)
	 * @required 
	 * @parameter
	 *     alias="moduleName" 
	 *     expression="${j2ts.moduleName}"
	 */
	private String moduleName;

	/**
	 * Path to output typescript folder
	 * The name will be <moduleName>.d.ts
	 * @required
	 * @parameter 
	 *    alias="tsOutFolder" 
	 * 		expression="${j2ts.tsOutFolder}" 
	 * 		default-value = "${project.build.directory}"
	 */
	private File tsOutFolder;

	/**
	 * Path to output Js file
	 * The name will be <moduleName>.js
	 * 
	 * @required
	 * @parameter 
	 *    alias="jsOutFolder"
	 * 		expression="${j2ts.jsOutFolder}" 
	 * 		default-value = "${project.build.directory}"
	 */
	private File jsOutFolder;

	/**
	 * An alternative JS template to module-template.js, given as String.
	 * Two placeholders will be replaced: %MODULE_NAME% and %JSON% (the service descriptor the js runtime can work with)
	 *
	 * @parameter
	 *    alias="jsTemplate"
	 * 		expression="${j2ts.jsTempalte}"
	 */
	private String jsTemplate;

	@Override
	public void execute() throws MojoExecutionException {

		try {

			// Descriptor for service
			ServiceDescriptorGenerator descGen = new ServiceDescriptorGenerator(getClasses());
			descGen.setAlternateJsTemplate(jsTemplate);

			// To Typescript
			{
				Writer writer = createFileAndGetWriter(tsOutFolder, moduleName + ".d.ts");
				Module tsModule = descGen.generateTypeScript(moduleName, new Configuration());
				tsModule.write(writer);
				writer.close();
			}

			// To JS
			{
				Writer outFileWriter = createFileAndGetWriter(jsOutFolder, moduleName + ".js");
				descGen.generateJavascript(moduleName, outFileWriter);
				outFileWriter.close();
			}

		} catch (Exception e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	private Collection<? extends Class<?>> getClasses() throws ClassNotFoundException {
		Collection<Class<?>> classes = Lists.<Class<?>>newArrayList();
		if (restServiceClassName != null) {
			classes.add(Class.forName(restServiceClassName));
		}
		if (restServicePackageName != null) {
			FastClasspathScanner scanner = new FastClasspathScanner(restServicePackageName);
			scanner.matchClassesWithAnnotation(Path.class, classes::add);
			scanner.scan();
		}
		return classes;
	}

	private Writer createFileAndGetWriter(File folder, String fileName) throws IOException {
		File file = new File(folder, fileName);
		getLog().info("Create file : " + file.getCanonicalPath());
		file.getParentFile().mkdirs();
		file.createNewFile();
		FileOutputStream stream = new FileOutputStream(file);
		OutputStreamWriter writer = new OutputStreamWriter(stream);
		return writer;
	};
}
