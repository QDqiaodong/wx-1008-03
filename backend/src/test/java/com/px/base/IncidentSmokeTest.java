package com.px.base;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.px.base.entity.FlightRoute;
import com.px.base.repository.FlightRouteRepository;
import com.px.base.repository.GroundStaffRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 冒烟：在全量演示种子（真实航线 R-GALE、真实锚点、真实人员 S001/S003）上
 * 完成“建档—快照—进入调查”的最短闭环，确认种子数据与新功能兼容。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("itest")
class IncidentSmokeTest {

    @TestConfiguration
    static class RedisStubConfig {
        @Bean
        @Primary
        @SuppressWarnings("unchecked")
        RedisTemplate<String, Object> stubRedisTemplate() {
            return mock(RedisTemplate.class, RETURNS_DEEP_STUBS);
        }
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper om;
    @Autowired FlightRouteRepository routeRepository;
    @Autowired GroundStaffRepository staffRepository;

    @Test
    void 在真实种子航线上建档() throws Exception {
        long zhao = staffRepository.findByStaffCode("S001").orElseThrow().getId();
        long safety = staffRepository.findByStaffCode("S003").orElseThrow().getId();
        FlightRoute gale = routeRepository.findByRouteCode("R-GALE").orElseThrow();

        Map<String, Object> body = new HashMap<>();
        body.put("title", "冒烟事件-" + System.nanoTime());
        body.put("severity", "CRITICAL");
        body.put("foundTime", LocalDateTime.now().minusHours(2).withNano(0).toString());
        body.put("routeId", gale.getId());
        body.put("anchorIds", List.of());
        body.put("sceneNarrative", "冒烟：起飞前检查发现绳索磨损。");

        JsonNode created = om.readTree(mvc.perform(post("/api/incident").header("X-Staff-Id", zhao)
                        .contentType(MediaType.APPLICATION_JSON).content(om.writeValueAsString(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
        long id = created.path("id").asLong();
        assertThat(created.path("snapRouteCode").asText()).isEqualTo("R-GALE");
        assertThat(created.path("snapRouteName").asText()).isEqualTo("疾风极限线");
        assertThat(created.path("snapRouteWindLevel").asText()).isEqualTo("疾风");
        assertThat(created.path("reporterName").asText()).isEqualTo("赵卫东");

        // 安全主管看得到
        mvc.perform(get("/api/incident/{id}", id).header("X-Staff-Id", safety))
                .andExpect(status().isOk());
        // 赵进入调查
        mvc.perform(post("/api/incident/{id}/enter-investigating", id).header("X-Staff-Id", zhao)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isOk());
    }
}
