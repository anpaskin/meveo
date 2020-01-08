package org.meveo.model.security;

public enum DefaultRole {
	
	ADMIN("administrateur", -1L),
	SUPER_ADMIN("superAdministrateur", -2L),
	MODIFY_ALL_CE("ModifyAllCE", -3L),
	READ_ALL_CE("ReadAllCE", -4L),
	MARKETING("marketingManager", -5L),
	CUSTOMER_CARE_USER("CUSTOMER_CARE_USER", -6L),
	GIT_ADMIN("gitAdmin", -7L);
	
	private String roleName;
	private Long id;

	private DefaultRole(String roleName, Long id) {
		this.roleName = roleName;
		this.id = id;
	}

	public String getRoleName() {
		return roleName;
	}

	public Long getId() {
		return id;
	}
	
	public Role get() {
		Role role = new Role();
		role.setId(id);
		role.setName(roleName);
		
		return role;
	}

}
