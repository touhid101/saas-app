package com.friendspharma.app.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.friendspharma.app.security.JwtAuthenticationFilter;
import com.friendspharma.app.security.ForcedPasswordChangeFilter;
import com.friendspharma.app.security.TenantStatusFilter;
import com.friendspharma.app.service.impl.TenantStatusService;
import com.friendspharma.app.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.util.HashMap;
import java.util.Map;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtTokenProvider jwtTokenProvider;
    private final UserDetailsService userDetailsService;
    private final TenantStatusService tenantStatusService;
    private final ObjectMapper objectMapper;
    private final com.friendspharma.app.repository.AllBranchRepository branchRepo;
    private final com.friendspharma.app.repository.SubAuditRepository auditRepo;
    private final com.friendspharma.app.security.ecom.EcomProperties ecomProperties;
    private final com.friendspharma.app.security.ecom.EcomTokenProvider ecomTokenProvider;
    private final com.friendspharma.app.repository.EcomCustomerRepository ecomCustomerRepo;
    private final com.friendspharma.app.repository.AclUserRepository userRepo;

    /**
     * Who counts as a platform operator — one definition, held in BranchScopeService.
     *
     * <p>The platform's organisation id used to be read here as well, so the filter
     * could answer the question for itself. It answered it differently, and the two
     * answers disagreed for any account holding ROLE01 without the IS_ADMIN column
     * set. There is one test now and the filter asks it.
     */
    private final com.friendspharma.app.service.impl.BranchScopeService branchScope;

    /** The scheme new passwords are written in. */
    public static final String BCRYPT_ID = "bcrypt";

    /**
     * Passwords are hashed with BCrypt, and the ones stored before this change
     * are migrated as their owners sign in.
     *
     * <p>Every password written since the application was built went in as
     * plaintext. Rehashing them in a migration is impossible — a hash cannot be
     * derived from a hash — and forcing eleven people to reset at once buys
     * nothing here, because the only moment the plaintext is needed is the next
     * time each of them types it. So the encoder accepts both: a stored value
     * with no {@code {id}} prefix is matched as plaintext, and
     * {@link com.friendspharma.app.service.impl.AuthServiceImpl#login} rewrites
     * it as BCrypt the moment that match succeeds. Nobody is locked out and the
     * plaintext is gone within a few days of ordinary use.
     *
     * <p>New passwords are only ever written {@code {bcrypt}}-prefixed, so the
     * legacy path is reachable only by a row nobody has signed in with yet.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        Map<String, PasswordEncoder> encoders = new HashMap<>();
        encoders.put(BCRYPT_ID, new BCryptPasswordEncoder(12));

        DelegatingPasswordEncoder encoder = new DelegatingPasswordEncoder(BCRYPT_ID, encoders);

        // Only reached by a password stored before this change: those rows carry
        // no prefix at all, so there is no id for the delegator to look up.
        @SuppressWarnings("deprecation")
        PasswordEncoder legacyPlaintext =
                org.springframework.security.crypto.password.NoOpPasswordEncoder.getInstance();
        encoder.setDefaultPasswordEncoderForMatches(legacyPlaintext);

        return encoder;
    }

    @Bean
    public JwtAuthenticationFilter jwtAuthenticationFilter() {
        return new JwtAuthenticationFilter(jwtTokenProvider, userDetailsService);
    }

    /**
     * The subscription gate, immediately after authentication.
     *
     * <p>Order matters: it needs to know who is asking before it can ask whether
     * their pharmacy has paid, and it should run before any handler does work
     * that an unpaid tenant is not entitled to.
     */
    /**
     * Lets a platform operator act as one pharmacy, before anything reads a branch.
     *
     * <p>Ordered after authentication and before the subscription gate: it needs to
     * know who is asking, and the gate should then test the pharmacy being acted as
     * rather than the operator's own.
     */
    @Bean
    public com.friendspharma.app.security.ActingBranchFilter actingBranchFilter() {
        return new com.friendspharma.app.security.ActingBranchFilter(
                branchRepo, auditRepo, objectMapper, branchScope);
    }

    /**
     * The customer side of the door: feature flag, customer tokens, and the
     * rule that a staff session counts for nothing on a customer route. Only
     * ever sees /api/ecom/** (see its shouldNotFilter).
     */
    @Bean
    public com.friendspharma.app.security.ecom.EcomCustomerAuthenticationFilter ecomCustomerAuthenticationFilter() {
        return new com.friendspharma.app.security.ecom.EcomCustomerAuthenticationFilter(
                ecomProperties, ecomTokenProvider, ecomCustomerRepo, objectMapper);
    }

    @Bean
    public TenantStatusFilter tenantStatusFilter() {
        return new TenantStatusFilter(tenantStatusService, objectMapper);
    }

    /**
     * [V71] Holds an account to the one thing it may do while it carries a
     * temporary password.
     *
     * <p>Ordered last of the four, after authentication has established who is
     * asking and after the branch and subscription gates. Its refusal is about
     * the credential rather than the pharmacy, so it should be the final word
     * rather than pre-empting a tenancy answer that is more informative.
     */
    @Bean
    public ForcedPasswordChangeFilter forcedPasswordChangeFilter() {
        return new ForcedPasswordChangeFilter(userRepo, objectMapper);
    }

    @Bean
    public AuthenticationManager authenticationManager() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return new ProviderManager(provider);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/login", "/api/auth/refresh").permitAll()
                // Signing up needs no token by definition. It creates nothing —
                // one row in a queue that a Super Admin has to approve.
                .requestMatchers("/api/public/**").permitAll()
                // Customer sign-up and sign-in; everything else under /api/ecom needs a
                // customer token (or, under /api/ecom/admin, a staff one).
                .requestMatchers("/api/ecom/auth/register", "/api/ecom/auth/login", "/api/ecom/auth/refresh").permitAll()
                .requestMatchers("/api/ecom/public/**").permitAll()
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                .requestMatchers("/actuator/health", "/actuator/info").permitAll()
                .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                .requestMatchers("/uploads/**").permitAll()   // serve uploaded images publicly
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthenticationFilter(), UsernamePasswordAuthenticationFilter.class)
            .addFilterAfter(ecomCustomerAuthenticationFilter(), JwtAuthenticationFilter.class)
            .addFilterAfter(actingBranchFilter(), com.friendspharma.app.security.ecom.EcomCustomerAuthenticationFilter.class)
            .addFilterAfter(tenantStatusFilter(),
                    com.friendspharma.app.security.ActingBranchFilter.class)
            .addFilterAfter(forcedPasswordChangeFilter(), TenantStatusFilter.class);

        return http.build();
    }
}
