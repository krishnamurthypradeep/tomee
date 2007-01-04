/**
 *
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.apache.openejb.assembler.classic;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.JarEntry;
import java.util.Set;
import java.util.TreeSet;
import java.util.List;
import java.util.StringTokenizer;
import java.util.ArrayList;
import java.net.URL;

import org.apache.openejb.core.TemporaryClassLoader;
import org.apache.openejb.core.cmp.cmp2.Cmp2Generator;
import org.apache.openejb.core.cmp.cmp2.CmrField;
import org.apache.openejb.OpenEJB;
import org.apache.openejb.OpenEJBException;

public class Cmp2Builder {
    private final ClassLoader tempClassLoader;

    private File jarFile;
    private final Set<String> entries = new TreeSet<String>();
    private final AppInfo appInfo;

    public Cmp2Builder(AppInfo appInfo) throws OpenEJBException {
        this.appInfo = appInfo;

        // create a tempClassLoader
        try {
            List<URL> jars = null;
            jars = new ArrayList<URL>();
            for (EjbJarInfo info : appInfo.ejbJars) {
                jars.add(new File(info.jarPath).toURL());
            }
            for (ClientInfo info : appInfo.clients) {
                jars.add(new File(info.codebase).toURL());
            }
            for (String jarPath : appInfo.libs) {
                jars.add(new File(jarPath).toURL());
            }
            tempClassLoader = new TemporaryClassLoader(jars.toArray(new URL[]{}), OpenEJB.class.getClassLoader());
        } catch (Exception e) {
            throw new OpenEJBException("Unable to create temporary class loader", e);
        }
    }

    public File getJarFile() throws IOException {
        if (jarFile == null) {
            generate();
        }
        return jarFile;
    }

    private void generate() throws IOException {
        boolean threwException = false;
        JarOutputStream jarOutputStream = openJarFile();
        try {
            // Generate CMP2 implementation classes
            for (EjbJarInfo ejbJar : appInfo.ejbJars) {
                for (EnterpriseBeanInfo beanInfo : ejbJar.enterpriseBeans) {
                    if (beanInfo instanceof EntityBeanInfo) {
                        EntityBeanInfo entityBeanInfo = (EntityBeanInfo) beanInfo;
                        generateClass(jarOutputStream, entityBeanInfo);
                    }
                }
            }
        } catch (IOException e) {
            threwException = true;
            throw e;
        } finally {
            close(jarOutputStream);
            if (threwException) {
                jarFile.delete();
                jarFile = null;
            }
        }
    }

    private void generateClass(JarOutputStream jarOutputStream, EntityBeanInfo entityBeanInfo) throws IOException {
        // only generate for cmp2 classes
        if (entityBeanInfo.cmpVersion != 2) {
            return;
        }

        // don't generate if there is aleady an implementation class
        String className = entityBeanInfo.ejbClass + "_JPA";
        String entryName = className.replace(".", "/") + ".class";
        if (entries.contains(entryName) || tempClassLoader.getResource(entryName) != null) {
            return;
        }

        // load the bean class, which is used buy the generator
        Class<?> beanClass = null;
        try {
            beanClass = tempClassLoader.loadClass(entityBeanInfo.ejbClass);
        } catch (ClassNotFoundException e) {
            throw new IOException("Could not find entity bean class " + beanClass);
        }

        // generte the implementation class
        Cmp2Generator cmp2Generator = new Cmp2Generator(beanClass,
                entityBeanInfo.primKeyField,
                entityBeanInfo.cmpFieldNames.toArray(new String[entityBeanInfo.cmpFieldNames.size()]));
        for (CmrFieldInfo cmrFieldInfo : entityBeanInfo.cmrFields) {
            if (cmrFieldInfo.fieldName != null) {
                CmrField cmrField = new CmrField(cmrFieldInfo.fieldName,
                        cmrFieldInfo.fieldType,
                        cmrFieldInfo.mappedBy.roleSource.ejbClass,
                        cmrFieldInfo.mappedBy.roleSource.local,
                        cmrFieldInfo.mappedBy.fieldName);
                cmp2Generator.addCmrField(cmrField);
            }
        }
        byte[] bytes = cmp2Generator.generate();

        // add the generated class to the jar
        addJarEntry(jarOutputStream, entryName, bytes);
    }

    private void addJarEntry(JarOutputStream jarOutputStream, String fileName, byte[] bytes) throws IOException {
        // add all missing directory entried
        String path = "";
        for (StringTokenizer tokenizer = new StringTokenizer(fileName, "/"); tokenizer.hasMoreTokens();) {
            String part = tokenizer.nextToken();
            if (tokenizer.hasMoreTokens()) {
                path += part + "/";
                if (!entries.contains(path)) {
                    jarOutputStream.putNextEntry(new JarEntry(path));
                    jarOutputStream.closeEntry();
                    entries.add(path);
                }
            }
        }

        // write the bytes
        jarOutputStream.putNextEntry(new JarEntry(fileName));
        try {
            jarOutputStream.write(bytes);
        } finally {
            jarOutputStream.closeEntry();
            entries.add(fileName);
        }
    }

    private JarOutputStream openJarFile() throws IOException {
        if (jarFile != null) {
            throw new IllegalStateException("Jar file is closed");
        }
        jarFile = File.createTempFile("OpenEJB_Generated_", ".jar");
        jarFile.deleteOnExit();
        JarOutputStream jarOutputStream = new JarOutputStream(new FileOutputStream(jarFile));
        return jarOutputStream;
    }

    private void close(JarOutputStream jarOutputStream) {
        if (jarOutputStream != null) {
            try {
                jarOutputStream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
