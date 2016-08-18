package org.java2typescript.maven.plugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.ws.rs.Path;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner;
import java2typescript.jackson.module.Configuration;
import java2typescript.jackson.module.conf.typename.SimpleJacksonTSTypeNamingStrategy;
import java2typescript.jackson.module.grammar.AnyType;
import java2typescript.jackson.module.grammar.ClassType;
import java2typescript.jackson.module.grammar.FunctionType;
import java2typescript.jackson.module.grammar.Module;
import java2typescript.jackson.module.grammar.VoidType;
import java2typescript.jackson.module.grammar.base.AbstractNamedType;
import java2typescript.jackson.module.grammar.base.AbstractType;
import java2typescript.jackson.module.writer.ExternalModuleFormatWriter;
import java2typescript.jackson.module.writer.ModuleWriter;
import java2typescript.jaxrs.ServiceDescriptorGenerator;
import java2typescript.jaxrs.ServiceDescriptorGenerator.ExtraFieldProvider;
import rx.functions.Func1;

/**
 * Generate typescript file out of RESt service definition
 *
 * @goal generate-custom
 * @phase process-classes
 * @configurator include-project-dependencies
 * @requiresDependencyResolution compile+runtime
 */
public class CustomMojo extends AbstractMojo {

	private static class MyModuleWriter extends ExternalModuleFormatWriter {

		private final String whitelistPackage;

		public MyModuleWriter(String whitelistPackage) {
			this.whitelistPackage = whitelistPackage;
		}

		@Override
		public void write(Module module, Writer writer) throws IOException {
			Collection<AbstractNamedType> types = module.getNamedTypes().values();
			types.removeIf(type -> {
				if (type instanceof GeneratedClassType) {
					return false;
				}
				return !type.getJavaClass().getName().startsWith(whitelistPackage);
			});
			types.stream()
				.filter(type -> !(type instanceof GeneratedClassType))
				.filter(ClassType.class::isInstance)
				.map(ClassType.class::cast)
				.forEach(type -> {
					type.getMethods().values().forEach(this::wrapInObservable);
				});
			writer.write("import { Observable } from 'rxjs/Observable';\n\n");
			super.write(module, writer);
		}

		private void wrapInObservable(FunctionType method) {
			method.setResultType(wrapInObservable(method.getResultType()));
		}

		private AbstractType wrapInObservable(AbstractType original) {
			if (original instanceof VoidType) return original;
			return new AbstractType() {
				@Override
				public void write(Writer writer) throws IOException {
					writer.write("Observable<");
					original.write(writer);
					writer.write(">");
				}
			};
		}

	}

	private static class GeneratedClassType extends ClassType {

		public GeneratedClassType(String className) {
			super(className, null);
		}
	}

	private static class ClientFactoryType extends GeneratedClassType {

		public ClientFactoryType(Collection<ClassType> serviceTypes) {
			super("ClientFactory");
			addMethods(getMethods(), serviceTypes);
		}

		private void addMethods(Map<String, FunctionType> target, Collection<ClassType> services) {
			for (ClassType service : services) {
				String name = service.getName();
				FunctionType method = new FunctionType();
				method.setResultType(service);
				target.put("get" + name, method);
			}
		}

	}

	private static class ServerFactoryType extends GeneratedClassType {

		private static class TypeParameterType extends AbstractType {

			@Override
			public void write(Writer writer) throws IOException {
				writer.write("T");
			}

		}

		public ServerFactoryType(Collection<ClassType> serviceTypes) {
			super("ServerFactory<T>");
			addMethods(getMethods(), serviceTypes);
		}

		private void addMethods(Map<String, FunctionType> target, Collection<ClassType> services) {
			for (ClassType service : services) {
				String name = service.getName();
				FunctionType method = new FunctionType();
				method.setResultType(new TypeParameterType());
				method.getParameters().put("service", service);
				target.put("for" + name, method);
			}
		}

	}

	/**
	 * @parameter
	 * @required
	 *    alias="moduleName"
	 *    expression="${j2ts.moduleName}"
	 */
	protected String moduleName;
	/**
	 * @parameter
	 *    alias="whitelistPackage"
	 *    expression="${j2ts.whitelistPackage}"
	 */
	protected String whitelistPackage = "";
	/**
	 * @parameter
	 *    alias="restServicePackageName"
	 *    expression="${j2ts.restServicePackageName}"
	 */
	protected String restServicePackageName = "";
	/**
	 * @parameter
	 *    alias="serviceExtraFieldProvider"
	 *    expression="${j2ts.serviceExtraFieldProvider}"
	 */
	protected String serviceExtraFieldProvider = "";
	/**
	 * @parameter
	 *    alias="methodExtraFieldProvider"
	 *    expression="${j2ts.methodExtraFieldProvider}"
	 */
	protected String methodExtraFieldProvider = "";
	/**
	 * @parameter
	 *    alias="typingsOutFolder"
	 *    expression="${j2ts.typingsOutFolder}"
	 */
	protected File typingsOutFolder = new File("target/typings");
	/**
	 * @parameter
	 *    alias="metadataOutFolder"
	 *    expression="${j2ts.metadataOutFolder}"
	 */
	protected File metadataOutFolder = new File("target/js");
	/**
	 * @parameter
	 *    alias="jsTemplate"
	 *    expression="${j2ts.jsTemplate}"
	 */
	protected String jsTemplate = null;

	@Override
	public void execute() throws MojoExecutionException {
		try {
			generate();
		} catch (Exception e) {
			throw new MojoExecutionException(e.getMessage(), e);
		}
	}

	public void generate() throws Exception {
		ExtraFieldProvider extras = createExtraFieldProvider();
		ServiceDescriptorGenerator generator = new ServiceDescriptorGenerator(getClasses(), new ObjectMapper(), extras);
		if (jsTemplate == null || jsTemplate.isEmpty()) {
			generator.setAlternateJsTemplate("exports.metaData = %JSON%;");
		} else {
			generator.setAlternateJsTemplate(jsTemplate);
		}

		Writer typeDefOut = createFileAndGetWriter(typingsOutFolder, moduleName + ".d.ts");
		Configuration configuration = new Configuration();
		configuration.setNamingStrategy(new SimpleJacksonTSTypeNamingStrategy() {
			@Override
			public String getName(JavaType type) {
				if (type.hasGenericTypes()) {
					if (type.getRawClass().getName().equals("rx.Observable")) {
						type = type.containedType(0);
					}
				}
				return super.getName(type);
			}
		});
		Module module = generator.generateTypeScript(moduleName, configuration);

		// remove all module variables, as we have a factory type instead
		module.getVars().clear();
		module.getVars().put("metaData", AnyType.getInstance());

		// add ClientFactory and ServerFactory types
		List<ClassType> serviceClasses = getServiceClasses(module);
		ClientFactoryType clientFactory = new ClientFactoryType(serviceClasses);
		ServerFactoryType serverFactory = new ServerFactoryType(serviceClasses);
		module.getNamedTypes().put(clientFactory.getName(), clientFactory);
		module.getNamedTypes().put(serverFactory.getName(), serverFactory);

		ModuleWriter moduleWriter = new MyModuleWriter(whitelistPackage);
		moduleWriter.write(module, typeDefOut);
		typeDefOut.close();

		Writer implOut = createFileAndGetWriter(metadataOutFolder, moduleName + ".js");
		generator.generateJavascript(moduleName, implOut);
		implOut.close();
	}

	private List<ClassType> getServiceClasses(Module module) throws ClassNotFoundException {
		Map<String, AbstractNamedType> types = module.getNamedTypes();
		return getClasses().stream()
			.map(Class::getSimpleName)
			.map(types::get)
			.map(ClassType.class::cast)
			.collect(Collectors.toList());
	}

	@SuppressWarnings("unchecked")
	private ExtraFieldProvider createExtraFieldProvider() throws ClassNotFoundException,
			InstantiationException, IllegalAccessException {
		final Func1<Class<?>, Object> forService;
		if (serviceExtraFieldProvider != null) {
			forService = (Func1<Class<?>, Object>) Class.forName(serviceExtraFieldProvider).newInstance();
		} else {
			forService = x -> null;
		}
		final Func1<Method, Object> forMethod;
		if (serviceExtraFieldProvider != null) {
			forMethod = (Func1<Method, Object>) Class.forName(methodExtraFieldProvider).newInstance();
		} else {
			forMethod = x -> null;
		}
		return new ExtraFieldProvider() {
			@Override
			public Object getExtraForService(Class<?> clazz) {
				return forService.call(clazz);
			}

			@Override
			public Object getExtraForMethod(Method method) {
				return forMethod.call(method);
			}
		};
	}

	private Collection<? extends Class<?>> getClasses() throws ClassNotFoundException {
		Collection<Class<?>> classes = new ArrayList<>();
		if (restServicePackageName != null) {
			FastClasspathScanner scanner = new FastClasspathScanner(restServicePackageName);
			scanner.matchClassesWithAnnotation(Path.class, classes::add);
			scanner.scan();
		}
		return classes;
	}

	private Writer createFileAndGetWriter(File folder, String fileName) throws IOException {
		File file = new File(folder, fileName);
		file.getParentFile().mkdirs();
		file.createNewFile();
		FileOutputStream stream = new FileOutputStream(file);
		return new OutputStreamWriter(stream);
	}

}
