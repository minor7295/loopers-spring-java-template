dependencies {
    // add-ons
    implementation(project(":modules:jpa"))
    implementation(project(":modules:redis"))
    implementation(project(":supports:jackson"))
    implementation(project(":supports:logging"))
    implementation(project(":supports:monitoring"))

    // web
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:${project.properties["springDocOpenApiVersion"]}")

    // batch
    implementation("org.springframework.boot:spring-boot-starter-batch")

    // faker
    implementation("net.datafaker:datafaker:2.0.2")

    // querydsl
    annotationProcessor("com.querydsl:querydsl-apt::jakarta")
    annotationProcessor("jakarta.persistence:jakarta.persistence-api")
    annotationProcessor("jakarta.annotation:jakarta.annotation-api")

    // test-fixtures
    testImplementation(testFixtures(project(":modules:jpa")))
    testImplementation(testFixtures(project(":modules:redis")))
}

// 데이터 시딩 실행을 위한 task
tasks.register<JavaExec>("runSeeding") {
    group = "application"
    description = "데이터 시딩 실행 (웹 서버 없이)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.loopers.infrastructure.seeding.DataSeedingApplication")
    systemProperty("spring.main.web-application-type", "none")
}
