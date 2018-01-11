/*
 * SonarQube Java
 * Copyright (C) 2012-2018 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.plugins.java;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.annotation.Nullable;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.signature.SignatureReader;
import org.objectweb.asm.signature.SignatureVisitor;
import org.sonar.api.batch.DependedUpon;
import org.sonar.api.batch.DependsUpon;
import org.sonar.api.batch.Phase;
import org.sonar.api.batch.fs.FileSystem;
import org.sonar.api.batch.fs.InputFile;
import org.sonar.api.batch.sensor.Sensor;
import org.sonar.api.batch.sensor.SensorContext;
import org.sonar.api.batch.sensor.SensorDescriptor;
import org.sonar.api.config.Configuration;
import org.sonar.api.issue.NoSonarFilter;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;
import org.sonar.java.DefaultJavaResourceLocator;
import org.sonar.java.JavaSquid;
import org.sonar.java.Measurer;
import org.sonar.java.SonarComponents;
import org.sonar.java.checks.CheckList;
import org.sonar.java.filters.PostAnalysisIssueFilter;
import org.sonar.java.model.JavaVersionImpl;
import org.sonar.plugins.java.api.JavaCheck;
import org.sonar.plugins.java.api.JavaVersion;

import static org.objectweb.asm.Opcodes.ASM6;

@Phase(name = Phase.Name.PRE)
@DependsUpon("BEFORE_SQUID")
@DependedUpon("squid")
public class JavaSquidSensor implements Sensor {

  private static final Logger LOG = Loggers.get(JavaSquidSensor.class);

  private final SonarComponents sonarComponents;
  private final FileSystem fs;
  private final DefaultJavaResourceLocator javaResourceLocator;
  private final Configuration settings;
  private final NoSonarFilter noSonarFilter;
  private final PostAnalysisIssueFilter postAnalysisIssueFilter;

  public JavaSquidSensor(SonarComponents sonarComponents, FileSystem fs,
                         DefaultJavaResourceLocator javaResourceLocator, Configuration settings, NoSonarFilter noSonarFilter, PostAnalysisIssueFilter postAnalysisIssueFilter) {
    this.noSonarFilter = noSonarFilter;
    this.sonarComponents = sonarComponents;
    this.fs = fs;
    this.javaResourceLocator = javaResourceLocator;
    this.settings = settings;
    this.postAnalysisIssueFilter = postAnalysisIssueFilter;
  }

  @Override
  public void describe(SensorDescriptor descriptor) {
    descriptor.onlyOnLanguage(Java.KEY).name("JavaSquidSensor");
  }

  @Override
  public void execute(SensorContext context) {
    javaResourceLocator.setSensorContext(context);
    sonarComponents.setSensorContext(context);

    List<Class<? extends JavaCheck>> checks = ImmutableList.<Class<? extends JavaCheck>>builder()
      .addAll(CheckList.getJavaChecks())
      .addAll(CheckList.getDebugChecks())
      .build();
    sonarComponents.registerCheckClasses(CheckList.REPOSITORY_KEY, checks);
    sonarComponents.registerTestCheckClasses(CheckList.REPOSITORY_KEY, CheckList.getJavaTestChecks());
    Measurer measurer = new Measurer(fs, context, noSonarFilter);
    JavaSquid squid = new JavaSquid(getJavaVersion(), isXFileEnabled(), sonarComponents, measurer, javaResourceLocator, postAnalysisIssueFilter, sonarComponents.checkClasses());
    squid.scan(getSourceFiles(), getTestFiles());
    computeAndDisplayDependencies(/*fs.inputFiles(fs.predicates().hasStatus(InputFile.Status.SAME))*/);
  }

  private Iterable<File> getSourceFiles() {
    return toFile(fs.inputFiles(fs.predicates().and(fs.predicates().hasLanguage(Java.KEY), fs.predicates().hasType(InputFile.Type.MAIN))));
  }

  private Iterable<File> getTestFiles() {
    return toFile(fs.inputFiles(fs.predicates().and(fs.predicates().hasLanguage(Java.KEY), fs.predicates().hasType(InputFile.Type.TEST))));
  }

  private static Iterable<File> toFile(Iterable<InputFile> inputFiles) {
    return StreamSupport.stream(inputFiles.spliterator(), false).map(InputFile::file).collect(Collectors.toList());
  }

  private JavaVersion getJavaVersion() {
    JavaVersion javaVersion = JavaVersionImpl.fromString(settings.get(Java.SOURCE_VERSION).orElse(null));
    LOG.info("Configured Java source version (" + Java.SOURCE_VERSION + "): " + javaVersion);
    return javaVersion;
  }

  private boolean isXFileEnabled() {
    return settings.getBoolean("sonar.java.xfile").orElse(false);
  }

  @Override
  public String toString() {
    return getClass().getSimpleName();
  }

  private void computeAndDisplayDependencies() {
    if(javaResourceLocator.classFilesToAnalyze().isEmpty()) {
      return;
    }
    String dotDeps = "digraph G {node [color = aliceblue,style = filled, fontname = \"Helvetica-Outline\" ];";
    Iterable<InputFile> inputFiles = fs.inputFiles(fs.predicates().not(fs.predicates().hasStatus(InputFile.Status.SAME)));
    System.out.println("---------s>"+Iterables.size(inputFiles));
    for (InputFile inputFile : inputFiles) {
      dotDeps+="\""+inputFile.filename()+"\"[color=crimson];";
    }
    for (File classFile : javaResourceLocator.classFilesToAnalyze()) {
      try (FileInputStream fis = new FileInputStream(classFile)) {
        DependencyVisitor dependencyVisitor = new DependencyVisitor();
        new ClassReader(fis).accept(dependencyVisitor, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        InputFile currentFile = javaResourceLocator.findResourceByClassName(dependencyVisitor.currentClass);
        if(currentFile == null) {
          continue;
        }
        dotDeps+="\""+currentFile.filename()+"\";";
        String filename = currentFile.filename();
        dotDeps += dependencyVisitor.dependencies.stream()
          .map(d-> javaResourceLocator.findResourceByClassName(d))
          .filter(Objects::nonNull)
          .filter(i -> !i.filename().equals(filename))
          .map(i -> {
            String res = "\"" + filename + "\"" + " -> \"" + i.filename() + "\";";
            if(i.status() != InputFile.Status.SAME) {
              res += "\"" + filename + "\"[shape=diamond];";
            }
            return res;
          })
          .collect(Collectors.joining(""));
      } catch (IOException ioe) {
        ioe.printStackTrace();
      }
    }
    dotDeps+="}";
    System.out.println("=============================================");
    System.out.println("=============================================");
    System.out.println(dotDeps);
    System.out.println("=============================================");
    System.out.println("=============================================");
  }

  public static class DependencyVisitor extends ClassVisitor {
    Set<String> dependencies = new HashSet<>();
    String currentClass;
    public DependencyVisitor() {
      super(ASM6);
    }

    private void addDesc(String desc) {
      addType(Type.getType(desc));
    }

    private void addType(Type t) {
      switch(t.getSort()) {
        case Type.ARRAY:
          addType(t.getElementType());
          break;
        case Type.OBJECT:
          addName(t.getClassName().replace('.','/'));
          break;
      }
    }
    private void addNames(@Nullable String[] names) {
      if(names == null) return;
      for (String name : names) {
        addName(name);
      }
    }

    private void addName(String name) {
      if (name == null) {
        return;
      }
      dependencies.add(name);
    }
    private void addMethodDesc(String desc) {
      addType(Type.getReturnType(desc));
      Type[] types = Type.getArgumentTypes(desc);
      for(int i = 0; i < types.length; i++) {
        addType(types[i]);
      }
    }
    private void addSignature(String sign) {
      new SignatureReader(sign).accept(new DependencySignature());
    }

    @Override
    public void visitEnd() {
      dependencies.remove(currentClass);
    }

    @Override
    public void visit(int version, int access, String name, @Nullable String signature, String superName, String[] interfaces) {
      currentClass = name;
      if (signature == null) {
        addName(superName);
        addNames(interfaces);
      } else {
        addSignature(signature);
      }
    }

    @Override
    public FieldVisitor visitField(int access, String name, String desc, @Nullable String signature, Object value) {
      if (signature == null) {
        addDesc(desc);
      } else {
        addSignature(signature);
      }
      if (value instanceof Type) {
        addType((Type) value);
      }
      return new DependencyField();
    }

    @Override
    public MethodVisitor visitMethod(int access, String name, String desc, @Nullable String signature, String[] exceptions) {
      if (signature == null) {
        addMethodDesc(desc);
      } else {
        addSignature(signature);
      }
      addNames(exceptions);
      return new DependencyMethod();
    }

    @Override
    public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
      addDesc(desc);
      return new DependencyAnnotation();
    }

    @Override
    public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
      return visitAnnotation(desc, visible);
    }

    // subvisitors
    private class DependencyField extends FieldVisitor {

      DependencyField() {
        super(ASM6);
      }

      @Override
      public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return DependencyVisitor.this.visitAnnotation(desc, visible);
      }

      @Override
      public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
        return DependencyVisitor.this.visitTypeAnnotation(typeRef, typePath, desc, visible);
      }
    }

    private class DependencyMethod extends MethodVisitor {
      DependencyMethod() {
        super(ASM6);
      }

      @Override
      public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
        return DependencyVisitor.this.visitAnnotation(desc, visible);
      }
      @Override
      public AnnotationVisitor visitTypeAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
        return DependencyVisitor.this.visitTypeAnnotation(typeRef, typePath, desc, visible);
      }

      @Override
      public AnnotationVisitor visitInsnAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
        return DependencyVisitor.this.visitTypeAnnotation(typeRef, typePath, desc, visible);
      }

      @Override
      public AnnotationVisitor visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index, String desc, boolean visible) {
        return DependencyVisitor.this.visitTypeAnnotation(typeRef, typePath, desc, visible);
      }

      @Override
      public AnnotationVisitor visitParameterAnnotation(int parameter, String desc, boolean visible) {
        return DependencyVisitor.this.visitAnnotation(desc, visible);
      }

      @Override
      public AnnotationVisitor visitTryCatchAnnotation(int typeRef, TypePath typePath, String desc, boolean visible) {
        return DependencyVisitor.this.visitTypeAnnotation(typeRef, typePath, desc, visible);
      }

      @Override
      public void visitTypeInsn(int opcode, String type) {
        addType(Type.getObjectType(type));
      }

      @Override
      public void visitFieldInsn(int opcode, String owner, String name, String desc) {
        addName(owner);
        addDesc(desc);
        super.visitFieldInsn(opcode, owner, name, desc);
      }

      @Override
      public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean itf) {
        addName(owner);
        addMethodDesc(desc);
        super.visitMethodInsn(opcode, owner, name, desc, itf);
      }

      @Override
      public void visitMultiANewArrayInsn(String desc, int dims) {
        addDesc(desc);
        super.visitMultiANewArrayInsn(desc, dims);
      }

      @Override
      public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
        addName(type);
        super.visitTryCatchBlock(start, end, handler, type);
      }
    }

    private class DependencyAnnotation extends AnnotationVisitor {
      DependencyAnnotation() {
        super(ASM6);
      }

      @Override
      public AnnotationVisitor visitAnnotation(String name, String desc) {
        addDesc(desc);
        return this;
      }
    }

    private class DependencySignature extends SignatureVisitor {

      DependencySignature() {
        super(ASM6);
      }

      @Override
      public void visitClassType(String name) {
        addName(name);
      }

      @Override
      public void visitInnerClassType(String name) {
        addName(name);
      }
    }

  }
}
