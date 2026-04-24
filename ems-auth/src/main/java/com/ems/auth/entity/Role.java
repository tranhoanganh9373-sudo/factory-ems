package com.ems.auth.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "roles")
public class Role {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 32) private String code;
    @Column(nullable = false, length = 64) private String name;
    @Column(length = 255) private String description;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public void setId(Long v) { this.id = v; }
    public void setCode(String v) { this.code = v; }
    public void setName(String v) { this.name = v; }
    public void setDescription(String v) { this.description = v; }
}
