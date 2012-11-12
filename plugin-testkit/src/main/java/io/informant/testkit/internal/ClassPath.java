/**
 * Copyright 2011-2012 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.informant.testkit.internal;

import java.io.File;

import javax.annotation.Nullable;

/**
 * @author Trask Stalnaker
 * @since 0.5
 */
public final class ClassPath {

    @Nullable
    public static File getInformantCoreJarFile() {
        return getJarFile("informant-core-[0-9.]+(-SNAPSHOT)?.jar");
    }

    @Nullable
    public static File getDelegatingJavaagentJarFile() {
        return getJarFile("delegating-javaagent-[0-9.]+(-SNAPSHOT)?.jar");
    }

    @Nullable
    private static File getJarFile(String pattern) {
        String classpath = System.getProperty("java.class.path");
        String[] classpathElements = classpath.split(File.pathSeparator);
        for (String classpathElement : classpathElements) {
            File classpathElementFile = new File(classpathElement);
            if (classpathElementFile.getName().matches(pattern)) {
                return classpathElementFile;
            }
        }
        return null;
    }

    private ClassPath() {}
}