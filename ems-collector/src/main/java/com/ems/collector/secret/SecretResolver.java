package com.ems.collector.secret;

import java.util.List;

public interface SecretResolver {
    String resolve(String ref);
    boolean exists(String ref);
    void write(String ref, String value);
    void delete(String ref);
    List<String> listRefs();
}
