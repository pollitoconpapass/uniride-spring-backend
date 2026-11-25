package com.uniride.agent;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;

@Tag("postgres")
@ActiveProfiles("postgres")
class PostgresCoordinacionConductorIntegrationTest extends CoordinacionConductorIntegrationTest { }
