package org.xiaoyu.gitarena;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class GitArenaApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitArenaApplication.class, args);
    }

}
