package com.hmdp;

import com.hmdp.config.MvcConfig;
import com.hmdp.config.WebExceptionAdvice;
import com.hmdp.controller.UserController;
import com.hmdp.dto.Result;
import com.hmdp.service.IUserInfoService;
import com.hmdp.service.IUserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisRepositoriesAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** Uses a real servlet container to cover its /error dispatch, with no middleware connections. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = LoginHttpBoundaryTest.TestApplication.class,
        properties = {"spring.config.name=login-boundary-test", "server.address=127.0.0.1",
                "logging.level.root=ERROR"})
class LoginHttpBoundaryTest {
    @Configuration
    @EnableAutoConfiguration(exclude = {DataSourceAutoConfiguration.class,
            RedisAutoConfiguration.class, RedisRepositoriesAutoConfiguration.class,
            RabbitAutoConfiguration.class})
    @Import({MvcConfig.class, UserController.class, WebExceptionAdvice.class, TestErrors.class})
    static class TestApplication {
    }

    @RestController
    static class TestErrors {
        @GetMapping("/shop/test-error")
        public void fail() throws IOException {
            throw new IOException("Simulated unhandled server failure");
        }
    }

    @Autowired
    private TestRestTemplate http;
    @MockBean
    private IUserService users;
    @MockBean
    private IUserInfoService userInfo;
    @MockBean
    private StringRedisTemplate redis;

    @Test
    void anonymousCodeRequestReachesService() throws Exception {
        when(users.sendCode(eq("13800000000"), any())).thenReturn(Result.ok());

        ResponseEntity<Result> response = http.postForEntity(
                "/user/code?phone=13800000000", null, Result.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getSuccess()).isTrue();
    }

    @Test
    void unhandledFailureKeepsItsServerErrorStatusThroughErrorDispatch() {
        ResponseEntity<String> response = http.getForEntity("/shop/test-error", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void missingParameterKeepsItsBadRequestStatusThroughErrorDispatch() {
        ResponseEntity<String> response = http.postForEntity("/user/code", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void privateApiStillRequiresLogin() {
        assertThat(http.getForEntity("/user/me", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void directRequestToErrorDoesNotBypassLogin() {
        assertThat(http.getForEntity("/error", String.class).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
