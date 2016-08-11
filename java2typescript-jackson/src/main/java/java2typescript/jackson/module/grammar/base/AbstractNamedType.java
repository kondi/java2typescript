/*******************************************************************************
 * Copyright 2013 Raphael Jolivet
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 ******************************************************************************/
package java2typescript.jackson.module.grammar.base;

import java.io.IOException;
import java.io.Writer;

import java2typescript.jackson.module.writer.WriterPreferences;

/** Type referenced by its name and capable of writing its own definition */
abstract public class AbstractNamedType extends AbstractType {

	protected final String name;
	private final Class<?> javaClass;

	public AbstractNamedType(String className, Class<?> javaClass) {
		this.name = className;
		this.javaClass = javaClass;
	}

	@Override
	public void write(Writer writer) throws IOException {
		writer.write(name);
	}

	public String getName() {
		return name;
	}

	public Class<?> getJavaClass() {
		return javaClass;
	}

	public void writeDef(Writer writer, WriterPreferences preferences) throws IOException {
		if(!preferences.hasCustomWriter(this)) {
			writeDefInternal(writer, preferences);
		} else {
			preferences.writeDef(this, writer);
		}
	}

	abstract public void writeDefInternal(Writer writer, WriterPreferences preferences) throws IOException;

}
