package amazon.account;

import java.util.Objects;
import java.util.Set;

/** Identity is immutable; account blocking affects subsequent operations. */
public final class Account {
    public enum Role { BUYER, SELLER, ADMIN }
    private final String id;
    private final String name;
    private final Set<Role> roles;
    private volatile boolean blocked;

    public Account(String id, String name, Set<Role> roles) {
        if (id == null || id.isBlank() || name == null || name.isBlank())
            throw new IllegalArgumentException("Missing account identity");
        this.id = id;
        this.name = name;
        this.roles = Set.copyOf(roles);
    }
    public String id() { return id; }
    public String name() { return name; }
    public void requireActive() {
        if (blocked) throw new IllegalStateException("Account is blocked");
    }
    public void require(Role role) {
        requireActive();
        if (!roles.contains(Objects.requireNonNull(role)))
            throw new SecurityException("Missing role: " + role);
    }
    public void block(Account administrator) {
        administrator.require(Role.ADMIN);
        blocked = true;
    }
}
