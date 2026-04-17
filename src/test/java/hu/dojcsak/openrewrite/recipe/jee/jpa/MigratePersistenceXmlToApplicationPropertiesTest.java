package hu.dojcsak.openrewrite.recipe.jee.jpa;

import org.junit.jupiter.api.Test;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.properties.Assertions.properties;
import static org.openrewrite.xml.Assertions.xml;

class MigratePersistenceXmlToApplicationPropertiesTest implements RewriteTest {

    private static final String PERSISTENCE_XML_PATH = "src/main/resources/META-INF/persistence.xml";
    private static final String APP_PROPS_PATH = "src/main/resources/application.properties";

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new MigratePersistenceXmlToApplicationProperties());
    }

    @Test
    void migratesHibernatePropertiesAndDeletesPersistenceXml() {
        rewriteRun(
                xml(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <persistence xmlns="http://java.sun.com/xml/ns/persistence" version="2.0">
                          <persistence-unit name="myPU">
                            <properties>
                              <property name="hibernate.dialect" value="org.hibernate.dialect.MySQL5InnoDBDialect"/>
                              <property name="hibernate.hbm2ddl.auto" value="update"/>
                              <property name="hibernate.connection.url" value="jdbc:mysql://localhost:3306/mydb"/>
                              <property name="hibernate.connection.username" value="root"/>
                              <property name="hibernate.connection.password" value="secret"/>
                            </properties>
                          </persistence-unit>
                        </persistence>
                        """,
                        (String) null,  // file is deleted
                        spec -> spec.path(PERSISTENCE_XML_PATH)
                ),
                properties(
                        null,   // file does not exist yet → generated
                        """
                        spring.jpa.database-platform=org.hibernate.dialect.MySQL5InnoDBDialect
                        spring.jpa.hibernate.ddl-auto=update
                        spring.datasource.url=jdbc:mysql://localhost:3306/mydb
                        spring.datasource.username=root
                        spring.datasource.password=secret
                        """,
                        spec -> spec.path(APP_PROPS_PATH)
                )
        );
    }

    @Test
    void migratesJavaxPersistenceJdbcProperties() {
        rewriteRun(
                xml(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <persistence>
                          <persistence-unit name="myPU">
                            <properties>
                              <property name="javax.persistence.jdbc.url" value="jdbc:oracle:thin:@localhost:1521:xe"/>
                              <property name="javax.persistence.jdbc.driver" value="oracle.jdbc.OracleDriver"/>
                              <property name="javax.persistence.jdbc.user" value="scott"/>
                              <property name="javax.persistence.jdbc.password" value="tiger"/>
                            </properties>
                          </persistence-unit>
                        </persistence>
                        """,
                        (String) null,
                        spec -> spec.path(PERSISTENCE_XML_PATH)
                ),
                properties(
                        null,
                        """
                        spring.datasource.url=jdbc:oracle:thin:@localhost:1521:xe
                        spring.datasource.driver-class-name=oracle.jdbc.OracleDriver
                        spring.datasource.username=scott
                        spring.datasource.password=tiger
                        """,
                        spec -> spec.path(APP_PROPS_PATH)
                )
        );
    }

    @Test
    void skipsUnmappedProperties() {
        // JNDI data sources and javax.persistence.transactionType have no Spring Boot equivalent
        // and must be migrated manually.
        rewriteRun(
                xml(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <persistence>
                          <persistence-unit name="myPU">
                            <properties>
                              <property name="hibernate.dialect" value="org.hibernate.dialect.H2Dialect"/>
                              <property name="javax.persistence.jtaDataSource" value="java:/MyDS"/>
                              <property name="javax.persistence.transactionType" value="JTA"/>
                            </properties>
                          </persistence-unit>
                        </persistence>
                        """,
                        (String) null,
                        spec -> spec.path(PERSISTENCE_XML_PATH)
                ),
                properties(
                        null,
                        // only the mapped property appears; jtaDataSource and transactionType are skipped
                        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect\n",
                        spec -> spec.path(APP_PROPS_PATH)
                )
        );
    }

    @Test
    void addsToExistingApplicationProperties() {
        rewriteRun(
                xml(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <persistence>
                          <persistence-unit name="myPU">
                            <properties>
                              <property name="hibernate.dialect" value="org.hibernate.dialect.H2Dialect"/>
                              <property name="hibernate.hbm2ddl.auto" value="create-drop"/>
                            </properties>
                          </persistence-unit>
                        </persistence>
                        """,
                        (String) null,
                        spec -> spec.path(PERSISTENCE_XML_PATH)
                ),
                properties(
                        "server.port=8080\n",
                        "server.port=8080\n" +
                        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect" +
                        "\nspring.jpa.hibernate.ddl-auto=create-drop",
                        spec -> spec.path(APP_PROPS_PATH)
                )
        );
    }

    @Test
    void doesNotAddAlreadyPresentProperties() {
        // spring.jpa.database-platform already present → not overwritten
        rewriteRun(
                xml(
                        """
                        <?xml version="1.0" encoding="UTF-8"?>
                        <persistence>
                          <persistence-unit name="myPU">
                            <properties>
                              <property name="hibernate.dialect" value="org.hibernate.dialect.H2Dialect"/>
                            </properties>
                          </persistence-unit>
                        </persistence>
                        """,
                        (String) null,
                        spec -> spec.path(PERSISTENCE_XML_PATH)
                ),
                properties(
                        "spring.jpa.database-platform=org.hibernate.dialect.MySQLDialect\n",
                        spec -> spec.path(APP_PROPS_PATH)
                )
        );
    }

    @Test
    void noOpWhenNoPersistenceXml() {
        rewriteRun(
                properties(
                        "server.port=8080\n",
                        spec -> spec.path(APP_PROPS_PATH)
                )
        );
    }
}
