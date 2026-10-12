package com.yeodam.yeodambe.common.exception;

import com.yeodam.yeodambe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

@SpringBootTest(properties = "management.endpoints.web.exposure.include=health")
@AutoConfigureMockMvc(addFilters = false)
@ActiveProfiles("test")
@Import(TestcontainersConfiguration.class)
class UnexposedActuatorEndpointTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void 노출하지_않은_프로메테우스_엔드포인트는_404를_반환한다() throws Exception {
        MvcResult result = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(content().json("""
                        {"message":"RESOURCE_NOT_FOUND","data":null}
                        """))
                .andReturn();

        assertThat(result.getResolvedException()).isInstanceOf(NoResourceFoundException.class);
        assertThat(result.getResponse().getStatus()).isEqualTo(404);
    }
}
