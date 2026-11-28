package com.uniride.agent.app;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.test.context.ActiveProfiles;

@Tag("postgres")
@ActiveProfiles("postgres")
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class PostgresAgentQueryPersistenceTest extends AgentQueryPersistenceTest { }
