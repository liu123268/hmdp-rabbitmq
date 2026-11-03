package com.hmdp;

import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.service.impl.UserServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class UserLoginServiceTest {
    private UserServiceImpl users;
    private StringRedisTemplate redis;
    private static final String PHONE = "13800000000";

    @BeforeEach
    void setUp() {
        users = new UserServiceImpl();
        redis = mock(StringRedisTemplate.class);
        ReflectionTestUtils.setField(users, "stringRedisTemplate", redis);
    }

    @Test
    void rejectsEmailAndInvalidPhoneBeforeReadingOrWritingRedis() {
        assertThat(users.sendCode("student@example.com", null).getSuccess()).isFalse();
        assertThat(users.sendCode("123", null).getSuccess()).isFalse();
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void createsSixDigitCodeWithExpiryAndRecordsSendTime() {
        SetOperations<String, String> sets = mock(SetOperations.class);
        ZSetOperations<String, String> times = mock(ZSetOperations.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(times);
        when(redis.opsForValue()).thenReturn(values);
        when(times.count(anyString(), anyDouble(), anyDouble())).thenReturn(0L);

        Result result = users.sendCode(PHONE, null);

        assertThat(result.getSuccess()).isTrue();
        assertThat(result.getData()).isNull();
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq(LOGIN_CODE_KEY + PHONE), code.capture(), eq(LOGIN_CODE_TTL), eq(TimeUnit.MINUTES));
        assertThat(code.getValue()).matches("[0-9]{6}");
        verify(times).add(eq(SENDCODE_SENDTIME_KEY + PHONE), anyString(), anyDouble());
    }

    @Test
    @SuppressWarnings("unchecked")
    void preservesOneMinuteCooldownWithoutOverwritingCode() {
        SetOperations<String, String> sets = mock(SetOperations.class);
        ZSetOperations<String, String> times = mock(ZSetOperations.class);
        when(redis.opsForSet()).thenReturn(sets);
        when(redis.opsForZSet()).thenReturn(times);
        when(times.count(anyString(), anyDouble(), anyDouble())).thenReturn(1L);

        assertThat(users.sendCode(PHONE, null).getSuccess()).isFalse();
        verify(redis, never()).opsForValue();
        verify(times, never()).add(anyString(), anyString(), anyDouble());
    }

    @Test
    void loginRejectsEmailBeforeAccessingRedis() {
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone("student@example.com");

        assertThat(users.login(form, null).getErrorMsg()).isEqualTo("手机号格式错误！");
        verifyNoInteractions(redis);
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingOrWrongCodeReturnsValidationError() {
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.get(LOGIN_CODE_KEY + PHONE)).thenReturn("123456");
        LoginFormDTO form = new LoginFormDTO();
        form.setPhone(PHONE);

        assertThat(users.login(form, null).getErrorMsg()).isEqualTo("无效的验证码");
        form.setCode("000000");
        assertThat(users.login(form, null).getErrorMsg()).isEqualTo("无效的验证码");
    }
}
