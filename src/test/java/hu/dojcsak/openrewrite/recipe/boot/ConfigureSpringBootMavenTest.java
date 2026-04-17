package hu.dojcsak.openrewrite.recipe.boot;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openrewrite.maven.Assertions.pomXml;
import static org.openrewrite.xml.Assertions.xml;

class ConfigureSpringBootMavenTest implements RewriteTest {

    private static final String VERSION = "2.7.18";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new ConfigureSpringBootMaven(VERSION));
    }

    // -------------------------------------------------------------------------
    // Single-module
    // -------------------------------------------------------------------------

    @Test
    void singleModuleAddsDependenciesAndPlugin() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>app</artifactId>
                            <version>1.0</version>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>app</artifactId>
                            <version>1.0</version>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                    <version>${spring-boot.version}</version>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <version>${spring-boot.version}</version>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>${spring-boot.version}</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """
                )
        );
    }

    // -------------------------------------------------------------------------
    // Multi-module: root pom
    // -------------------------------------------------------------------------

    @Test
    void multiModuleAddsPluginManagementToRootPom() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>ear</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                )
        );
    }

    // -------------------------------------------------------------------------
    // Multi-module: war-in-ear exclusion
    // -------------------------------------------------------------------------

    @Test
    void warIncludedInEarIsExcludedFromTargets() {
        // The ear module has a dependency on the war module
        // → war module must NOT receive starters or plugin
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        // root gets pluginManagement (with version)
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <packaging>war</packaging>
                        </project>
                        """,
                        // war is in ear → no change
                        spec -> spec.path("web/pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                )
        );
    }

    @Test
    void warNotInEarIsATarget() {
        // The war module is standalone (not pulled into ear) → receives starters and plugin
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <packaging>war</packaging>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("web/pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                )
        );
    }

    // -------------------------------------------------------------------------
    // weblogic-application.xml deletion
    // -------------------------------------------------------------------------

    @Test
    void weblogicApplicationXmlDeletedFromEarTargetModule() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>ear</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                ),
                // weblogic-application.xml must be deleted — EAR descriptor is obsolete after
                // the packaging change to jar.
                xml(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <weblogic-application xmlns="http://xmlns.oracle.com/weblogic/weblogic-application">
                            <listener>
                                <listener-class>com.example.LifecycleListener</listener-class>
                            </listener>
                        </weblogic-application>
                        """,
                        (String) null,
                        spec -> spec.path("ear/src/main/application/META-INF/weblogic-application.xml")
                )
        );
    }

    @Test
    void weblogicApplicationXmlNotDeletedFromNonTargetModule() {
        // war-in-ear is NOT a target module → its weblogic-application.xml must not be deleted.
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <packaging>war</packaging>
                        </project>
                        """,
                        // war-in-ear: not a target → no change
                        spec -> spec.path("web/pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                ),
                // weblogic-application.xml in the non-target war module must NOT be deleted.
                xml(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <weblogic-application xmlns="http://xmlns.oracle.com/weblogic/weblogic-application">
                            <listener>
                                <listener-class>com.example.LifecycleListener</listener-class>
                            </listener>
                        </weblogic-application>
                        """,
                        spec -> spec.path("web/src/main/application/META-INF/weblogic-application.xml")
                )
        );
    }

    // -------------------------------------------------------------------------
    // maven-ear-plugin removal
    // -------------------------------------------------------------------------

    @Test
    void multiModuleRemovesEarPluginFromTargetAndPluginManagement() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>ear</module>
                            </modules>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.apache.maven.plugins</groupId>
                                            <artifactId>maven-ear-plugin</artifactId>
                                            <version>2.3</version>
                                            <configuration>
                                                <defaultLibBundleDir>APP-INF/lib</defaultLibBundleDir>
                                            </configuration>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>ear</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-ear-plugin</artifactId>
                                        <configuration>
                                            <version>5</version>
                                            <modules>
                                                <ejbModule>
                                                    <groupId>com.example</groupId>
                                                    <artifactId>business</artifactId>
                                                </ejbModule>
                                            </modules>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                )
        );
    }

    @Test
    void singleModuleRemovesEarPluginFromBuildPlugins() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>app</artifactId>
                            <version>1.0</version>
                            <packaging>ear</packaging>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-ear-plugin</artifactId>
                                        <version>2.3</version>
                                        <configuration>
                                            <modules>
                                                <ejbModule>
                                                    <groupId>com.example</groupId>
                                                    <artifactId>business</artifactId>
                                                </ejbModule>
                                            </modules>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>app</artifactId>
                            <version>1.0</version>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                    <version>${spring-boot.version}</version>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <version>${spring-boot.version}</version>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>${spring-boot.version}</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """
                )
        );
    }

    @Test
    void earPluginNotRemovedFromNonTargetModules() {
        // war-in-ear is NOT a target → its ear plugin (unlikely but possible) stays untouched
        // More relevantly: only the EAR module loses its ear plugin, not the war-in-ear
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <packaging>war</packaging>
                        </project>
                        """,
                        // war-in-ear is not a target → no changes
                        spec -> spec.path("web/pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-ear-plugin</artifactId>
                                        <configuration>
                                            <modules>
                                                <webModule>
                                                    <groupId>com.example</groupId>
                                                    <artifactId>web</artifactId>
                                                </webModule>
                                            </modules>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                )
        );
    }

    // -------------------------------------------------------------------------
    // maven-war-plugin removal
    // -------------------------------------------------------------------------

    @Test
    void standaloneWarTargetRemovesWarPlugin() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                            </modules>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <packaging>war</packaging>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-war-plugin</artifactId>
                                        <version>3.3.2</version>
                                        <configuration>
                                            <failOnMissingWebXml>false</failOnMissingWebXml>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("web/pom.xml")
                )
        );
    }

    @Test
    void singleModuleWarRemovesWarPlugin() {
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>app</artifactId>
                            <version>1.0</version>
                            <packaging>war</packaging>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-war-plugin</artifactId>
                                        <version>3.3.2</version>
                                        <configuration>
                                            <failOnMissingWebXml>false</failOnMissingWebXml>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>app</artifactId>
                            <version>1.0</version>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <dependencies>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                    <version>${spring-boot.version}</version>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <version>${spring-boot.version}</version>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                        <version>${spring-boot.version}</version>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """
                )
        );
    }

    @Test
    void warInEarDoesNotRemoveWarPlugin() {
        // war-in-ear is NOT a target module → its maven-war-plugin must not be removed
        rewriteRun(
                pomXml(
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                        </project>
                        """,
                        """
                        <project>
                            <groupId>com.example</groupId>
                            <artifactId>parent</artifactId>
                            <version>1.0</version>
                            <packaging>pom</packaging>
                            <modules>
                                <module>web</module>
                                <module>ear</module>
                            </modules>
                            <properties>
                                <spring-boot.version>2.7.18</spring-boot.version>
                            </properties>
                            <dependencyManagement>
                                <dependencies>
                                    <dependency>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-dependencies</artifactId>
                                        <version>${spring-boot.version}</version>
                                        <type>pom</type>
                                        <scope>import</scope>
                                    </dependency>
                                </dependencies>
                            </dependencyManagement>
                            <build>
                                <pluginManagement>
                                    <plugins>
                                        <plugin>
                                            <groupId>org.springframework.boot</groupId>
                                            <artifactId>spring-boot-maven-plugin</artifactId>
                                            <version>${spring-boot.version}</version>
                                        </plugin>
                                    </plugins>
                                </pluginManagement>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>web</artifactId>
                            <packaging>war</packaging>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.apache.maven.plugins</groupId>
                                        <artifactId>maven-war-plugin</artifactId>
                                        <version>3.3.2</version>
                                        <configuration>
                                            <failOnMissingWebXml>false</failOnMissingWebXml>
                                        </configuration>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        // war-in-ear is not a target → war plugin stays, no starters added
                        spec -> spec.path("web/pom.xml")
                ),
                pomXml(
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <packaging>ear</packaging>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                            </dependencies>
                        </project>
                        """,
                        """
                        <project>
                            <parent>
                                <groupId>com.example</groupId>
                                <artifactId>parent</artifactId>
                                <version>1.0</version>
                            </parent>
                            <artifactId>ear</artifactId>
                            <dependencies>
                                <dependency>
                                    <groupId>com.example</groupId>
                                    <artifactId>web</artifactId>
                                    <version>1.0</version>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter</artifactId>
                                </dependency>
                                <dependency>
                                    <groupId>org.springframework.boot</groupId>
                                    <artifactId>spring-boot-starter-test</artifactId>
                                    <scope>test</scope>
                                </dependency>
                            </dependencies>
                            <build>
                                <plugins>
                                    <plugin>
                                        <groupId>org.springframework.boot</groupId>
                                        <artifactId>spring-boot-maven-plugin</artifactId>
                                    </plugin>
                                </plugins>
                            </build>
                        </project>
                        """,
                        spec -> spec.path("ear/pom.xml")
                )
        );
    }

    // -------------------------------------------------------------------------
    // resolveTargetPomPaths unit tests
    // -------------------------------------------------------------------------

    @Test
    void resolveTargetsSingleModule() {
        Map<java.nio.file.Path, ConfigureSpringBootMaven.PomModule> modules = new LinkedHashMap<>();
        modules.put(Paths.get("pom.xml"),
                new ConfigureSpringBootMaven.PomModule(
                        Paths.get("pom.xml"), "com.example", "app", "jar", new LinkedHashSet<>()));

        Set<java.nio.file.Path> targets =
                ConfigureSpringBootMaven.resolveTargetPomPaths(modules, false);
        assertThat(targets).containsExactly(Paths.get("pom.xml"));
    }

    @Test
    void resolveTargetsExcludesWarInEar() {
        Map<java.nio.file.Path, ConfigureSpringBootMaven.PomModule> modules = new LinkedHashMap<>();
        modules.put(Paths.get("pom.xml"),
                new ConfigureSpringBootMaven.PomModule(
                        Paths.get("pom.xml"), "com.example", "parent", "pom", new LinkedHashSet<>()));

        Set<String> earDeps = new LinkedHashSet<>();
        earDeps.add("com.example:web");
        modules.put(Paths.get("ear/pom.xml"),
                new ConfigureSpringBootMaven.PomModule(
                        Paths.get("ear/pom.xml"), "com.example", "ear", "ear", earDeps));
        modules.put(Paths.get("web/pom.xml"),
                new ConfigureSpringBootMaven.PomModule(
                        Paths.get("web/pom.xml"), "com.example", "web", "war", new LinkedHashSet<>()));

        Set<java.nio.file.Path> targets =
                ConfigureSpringBootMaven.resolveTargetPomPaths(modules, true);
        assertThat(targets)
                .contains(Paths.get("ear/pom.xml"))
                .doesNotContain(Paths.get("web/pom.xml"));
    }

    @Test
    void resolveTargetsIncludesStandaloneWar() {
        Map<java.nio.file.Path, ConfigureSpringBootMaven.PomModule> modules = new LinkedHashMap<>();
        modules.put(Paths.get("pom.xml"),
                new ConfigureSpringBootMaven.PomModule(
                        Paths.get("pom.xml"), "com.example", "parent", "pom", new LinkedHashSet<>()));
        modules.put(Paths.get("web/pom.xml"),
                new ConfigureSpringBootMaven.PomModule(
                        Paths.get("web/pom.xml"), "com.example", "web", "war", new LinkedHashSet<>()));

        Set<java.nio.file.Path> targets =
                ConfigureSpringBootMaven.resolveTargetPomPaths(modules, true);
        assertThat(targets).contains(Paths.get("web/pom.xml"));
    }
}
