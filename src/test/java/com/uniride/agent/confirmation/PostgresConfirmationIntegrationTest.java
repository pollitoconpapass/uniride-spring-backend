package com.uniride.agent.confirmation;

import org.junit.jupiter.api.Tag;
import org.springframework.test.context.ActiveProfiles;

/** Repeats the transaction/ownership/replay contracts with real PostgreSQL row locks and Flyway. */
@Tag("postgres")
@ActiveProfiles("postgres")
class PostgresConfirmationIntegrationTest extends ConfirmationServiceIntegrationTest { }
