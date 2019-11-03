package cz.polankam.security.acl;

import cz.polankam.security.acl.exceptions.PermissionException;
import cz.polankam.security.acl.exceptions.ResourceNotFoundException;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Custom permission evaluator used for 'hasPermission' expressions within
 * authorize annotations. The implementation is based on the user roles and
 * permission rules created by the {@link IPermissionsService} which has to be
 * given at construction.
 *
 * Created by Martin Polanka
 */
@Transactional(rollbackFor = Exception.class)
public class AclPermissionEvaluator implements PermissionEvaluator {

    /**
     * Permission service which contains definition of roles and resource
     * repositories used for evaluation.
     */
    private IPermissionsService permissionsService;

    /**
     * Constructor.
     * @param permissionsService roles definition service
     */
    public AclPermissionEvaluator(IPermissionsService permissionsService) {
        this.permissionsService = permissionsService;
    }


    /**
     * Determine if the given user with defined roles can perform action on the
     * resource.
     * @param authentication authentication containing currently logged user
     * @param targetDomainObject textual representation of the resource
     * @param permission textual representation of the action on the resource
     * @return true if user can perform the action on the given resource
     */
    @Override
    public boolean hasPermission(Authentication authentication, Object targetDomainObject, Object permission) {
        if (authentication == null ||
                !(authentication.getPrincipal() instanceof UserDetails) ||
                !(targetDomainObject instanceof String) ||
                !(permission instanceof String)){
            return false;
        }

        UserDetails user = (UserDetails) authentication.getPrincipal();
        String targetResource = (String) targetDomainObject;
        String permissionString = (String) permission;

        // check the permissions against all user roles
        for (GrantedAuthority authority : user.getAuthorities()) {
            Role role = permissionsService.getRole(authority.getAuthority());
            if (role == null) {
                // not defined role in permission service, strange, but let us continue...
                continue;
            }

            List<PermissionRule> rules = findMatching(role.getPermissionRules(targetResource), targetResource, permissionString);
            Optional<PermissionRule> firstRule = rules.stream().findFirst();
            if (firstRule.isPresent()) {
                if (firstRule.get().getCondition() != null) {
                    throw new PermissionException("ABAC permission rule for resource '" + targetResource + 
                        "' and action '" + permissionString + "' was used in non-ABAC context");
                }

                return firstRule.get().isAllowed();
            }
        }
        return false;
    }


    /**
     * Determine if the given user with defined roles can perform action on the
     * resource with given identification.
     * @param authentication authentication containing currently logged user
     * @param targetId identification of the resource which should be acquired
     * @param targetType textual representation of the resource
     * @param permission textual representation of the action on the resource
     * @return true if user can perform the action on the given resource
     */
    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId, String targetType, Object permission) {
        if (authentication == null ||
                !(authentication.getPrincipal() instanceof UserDetails) ||
                !(permission instanceof String)){
            return false;
        }

        UserDetails user = (UserDetails) authentication.getPrincipal();
        String permissionString = (String) permission;

        // check the permissions against all user roles
        for (GrantedAuthority authority : user.getAuthorities()) {
            Role role = permissionsService.getRole(authority.getAuthority());
            if (role == null) {
                // not defined role in permission service, strange, but let us continue...
                continue;
            }

            List<PermissionRule> rules = findMatching(role.getPermissionRules(targetType), targetType, permissionString);
            Optional<PermissionRule> firstRule = rules.stream().findFirst();
            if (firstRule.isPresent()) {
                PermissionRule rule = firstRule.get();

                // we have to find resource repository, because we were
                // given resource identification, after that resource is
                // acquired from the repository and evaluated in specified
                // condition
                IResourceRepository repository = permissionsService.getResource(rule.getResource());
                Optional<Object> resource = repository.findById(targetId);
                if (!resource.isPresent()) {
                    throw new ResourceNotFoundException("Resource with identification '" + targetId + "' not found");
                }

                if (rule.getCondition() != null) {
                    // condition was given, so evaluate it
                    if (rule.getCondition().test(user, resource.get())) {
                        return rule.isAllowed();
                    }

                    // if condition was false, we have to continue
                    // evaluating another rules, because some of them might
                    // be truthy and grant access to resource
                } else {
                    // condition was not given, so the behaviour is the same
                    // as for regular id-less permission check, allow it
                    return rule.isAllowed();
                }
            }
        }
        return false;
    }

    /**
     * Find all matching rules with given resource and action.
     * @param rules source rules
     * @param resource resource which should be found
     * @param action action which should be found
     * @return filtered collection of matching rules
     */
    private List<PermissionRule> findMatching(Collection<PermissionRule> rules, String resource, String action) {
        return rules.stream().filter(rule -> 
            Objects.equals(rule.getResource(), resource) && Objects.equals(rule.getAction(), action)
        ).collect(Collectors.toList());
    }
}
