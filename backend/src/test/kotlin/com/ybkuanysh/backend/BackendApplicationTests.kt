package com.ybkuanysh.backend

import org.junit.jupiter.api.Test
import com.ybkuanysh.backend.support.FixtureMlConfig
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(FixtureMlConfig::class)
class BackendApplicationTests {

    @Test
    fun contextLoads() {
    }

}
