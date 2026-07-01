package hu.dojcsak.openrewrite.recipe.jee.jpa;

import org.junit.jupiter.api.Test;
import org.openrewrite.DocumentExample;
import org.openrewrite.test.RecipeSpec;
import org.openrewrite.test.RewriteTest;

import static org.openrewrite.java.Assertions.java;

class FixDateToInstantTest implements RewriteTest {

    @Override
    public void defaults(RecipeSpec spec) {
        spec.recipe(new FixDateToInstant());
    }

    @DocumentExample
    @Test
    void replacesToInstantBeforeToLocalDate() {
        rewriteRun(
          java(
            """
              import java.time.LocalDate;
              import java.time.ZoneId;
              import java.util.Date;

              class A {
                  LocalDate toLocalDate(Date date) {
                      return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                  }
              }
              """,
            """
              import java.time.Instant;
              import java.time.LocalDate;
              import java.time.ZoneId;
              import java.util.Date;

              class A {
                  LocalDate toLocalDate(Date date) {
                      return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
                  }
              }
              """
          )
        );
    }

    @Test
    void replacesToInstantBeforeToLocalDateTime() {
        rewriteRun(
          java(
            """
              import java.time.LocalDateTime;
              import java.time.ZoneId;
              import java.util.Date;

              class A {
                  LocalDateTime toLocalDateTime(Date date) {
                      return date.toInstant().atZone(ZoneId.systemDefault()).toLocalDateTime();
                  }
              }
              """,
            """
              import java.time.Instant;
              import java.time.LocalDateTime;
              import java.time.ZoneId;
              import java.util.Date;

              class A {
                  LocalDateTime toLocalDateTime(Date date) {
                      return Instant.ofEpochMilli(date.getTime()).atZone(ZoneId.systemDefault()).toLocalDateTime();
                  }
              }
              """
          )
        );
    }

    @Test
    void replacesGetterCallOnEntity() {
        rewriteRun(
          java(
            """
              import java.time.LocalDate;
              import java.time.ZoneId;
              import java.util.Date;

              class DayInfo {
                  Date getDateOfDay() {
                      return new Date();
                  }
              }

              class A {
                  LocalDate localDate(DayInfo dayInfo) {
                      return dayInfo.getDateOfDay().toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
                  }
              }
              """,
            """
              import java.time.Instant;
              import java.time.LocalDate;
              import java.time.ZoneId;
              import java.util.Date;

              class DayInfo {
                  Date getDateOfDay() {
                      return new Date();
                  }
              }

              class A {
                  LocalDate localDate(DayInfo dayInfo) {
                      return Instant.ofEpochMilli(dayInfo.getDateOfDay().getTime()).atZone(ZoneId.systemDefault()).toLocalDate();
                  }
              }
              """
          )
        );
    }

    @Test
    void doesNotChangeReverseConversion() {
        rewriteRun(
          java(
            """
              import java.time.LocalDateTime;
              import java.time.ZoneId;
              import java.util.Date;

              class A {
                  Date fromLocalDateTime(LocalDateTime localDateTime) {
                      return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
                  }
              }
              """
          )
        );
    }

    @Test
    void doesNotChangeTimestampToInstant() {
        rewriteRun(
          java(
            """
              import java.sql.Timestamp;

              class A {
                  java.time.Instant toInstant(Timestamp timestamp) {
                      return timestamp.toInstant();
                  }
              }
              """
          )
        );
    }
}
