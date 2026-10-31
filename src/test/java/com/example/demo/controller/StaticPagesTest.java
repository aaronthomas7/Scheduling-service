package com.example.demo.controller;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** The host UI is plain static files; this catches broken paths between the page and its CSS/JS. */
@SpringBootTest
@AutoConfigureMockMvc
class StaticPagesTest {

    @Autowired
    MockMvc mvc;

    @Test
    void hostPageAndItsAssetsAreServed() throws Exception {
        mvc.perform(get("/host.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"availability-form\"")))
                .andExpect(content().string(containsString("src=\"js/host.js\"")))
                .andExpect(content().string(containsString("href=\"css/host.css\"")));
        mvc.perform(get("/js/host.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("from './api.js'")))
                .andExpect(content().string(containsString("from './week.js'")));
        mvc.perform(get("/js/api.js")).andExpect(status().isOk())
                .andExpect(content().string(containsString("'publish-availability'")));
        mvc.perform(get("/js/week.js")).andExpect(status().isOk());
        mvc.perform(get("/css/host.css")).andExpect(status().isOk());
    }

    @Test
    void landingPageLinksToTheHostPage() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"host.html\"")));
    }
}
