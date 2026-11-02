#!/usr/bin/env bash
# Applies V1__schema.sql to H2 (PostgreSQL mode) and, if a server is reachable, to PostgreSQL, then runs the constraint checks.
# Needs only a JDK and the H2 / PostgreSQL JDBC jars (Ubuntu: apt install libh2-java libpostgresql-jdbc-java). No Maven.
#   PG_URL=jdbc:postgresql://localhost:5432/sched PG_USER=postgres PG_PASSWORD=pw ./run-schema-check.sh
set -euo pipefail
cd "$(dirname "$0")"
SQL=src/main/resources/db/migration/V1__schema.sql
H2="${H2_JAR:-/usr/share/java/h2.jar}"; PGJ="${PG_JAR:-/usr/share/java/postgresql.jar}"
rm -rf out && mkdir -p out && javac -d out src/test/java/schema/*.java
java -cp "out:$H2" schema.SchemaCheck "jdbc:h2:mem:sched;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DEFAULT_NULL_ORDERING=HIGH" sa "" "$SQL"
if [ -n "${PG_URL:-}" ]; then
  java -cp "out:$PGJ" schema.SchemaCheck "$PG_URL" "${PG_USER:-postgres}" "${PG_PASSWORD:-}" "$SQL"
fi
