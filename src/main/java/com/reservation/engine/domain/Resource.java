package com.reservation.engine.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Bookable resource identity (a flight, a hotel, an event...). Inventory is held by model-specific tables. */
@Entity
@Table(name = "resource")
public class Resource {

    @Id
    private UUID id;

    @Column(name = "type_code", nullable = false, length = 50)
    private String typeCode;

    @Column(name = "inventory_model", nullable = false, length = 30)
    private String inventoryModel;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(length = 2000)
    private String description;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> attributes = new HashMap<>();

    @Column(name = "price_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal priceAmount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected Resource() {
    }

    public Resource(String typeCode, String inventoryModel, String name, String description,
                    Map<String, Object> attributes, Money price, Instant now) {
        this.id = UUID.randomUUID();
        this.typeCode = typeCode;
        this.inventoryModel = inventoryModel;
        this.name = name;
        this.description = description;
        this.attributes = attributes == null ? new HashMap<>() : new HashMap<>(attributes);
        this.priceAmount = price.amount();
        this.currency = price.currency();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() { return id; }
    public String getTypeCode() { return typeCode; }
    public String getInventoryModel() { return inventoryModel; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public Map<String, Object> getAttributes() { return Map.copyOf(attributes); }
    public Money getPrice() { return new Money(priceAmount, currency); }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
    public Long getVersion() { return version; }

    public void deactivate(Instant now) {
        this.active = false;
        this.updatedAt = now;
    }
}
