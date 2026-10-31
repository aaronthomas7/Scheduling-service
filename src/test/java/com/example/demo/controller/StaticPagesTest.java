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

/** The pages are plain static files; this catches broken paths between a page and its CSS/JS modules. */
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
                .andExpect(content().string(containsString("id=\"bookings\"")))
                .andExpect(content().string(containsString("src=\"js/host.js\"")))
                .andExpect(content().string(containsString("href=\"css/app.css\"")));
        mvc.perform(get("/js/host.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("from './api.js'")))
                .andExpect(content().string(containsString("from './week.js'")))
                .andExpect(content().string(containsString("from './zones.js'")))
                .andExpect(content().string(containsString("from './dom.js'")));
        mvc.perform(get("/css/app.css")).andExpect(status().isOk());
    }

    @Test
    void bookingPageAndItsAssetsAreServed() throws Exception {
        mvc.perform(get("/book.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("id=\"days\"")))
                .andExpect(content().string(containsString("src=\"js/book.js\"")))
                .andExpect(content().string(containsString("href=\"css/app.css\"")));
        mvc.perform(get("/js/book.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("from './api.js'")))
                .andExpect(content().string(containsString("from './zones.js'")))
                .andExpect(content().string(containsString("from './dom.js'")));
    }

    @Test
    void sharedModulesCallTheRealEndpoints() throws Exception {
        mvc.perform(get("/js/api.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("'publish-availability'")))
                .andExpect(content().string(containsString("get-available-slots/")))
                .andExpect(content().string(containsString("'book-slot'")))
                .andExpect(content().string(containsString("/bookings/reschedule")))
                .andExpect(content().string(containsString("api/hosts/${hostId}/bookings")));
        for (String module : new String[] {"/js/week.js", "/js/zones.js", "/js/dom.js"}) {
            mvc.perform(get(module)).andExpect(status().isOk());
        }
    }

    @Test
    void landingPageLinksToBothPages() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("href=\"host.html\"")))
                .andExpect(content().string(containsString("href=\"book.html?hosts=1\"")));
    }
}
