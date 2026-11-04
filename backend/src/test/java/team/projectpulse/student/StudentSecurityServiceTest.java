package team.projectpulse.student;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import team.projectpulse.system.UserUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/** UC-STU-deactivate-student T-deny: lifecycle guards fail closed without exposing lookup failures. */
@ExtendWith(MockitoExtension.class)
class StudentSecurityServiceTest {
    @Mock StudentRepository students;
    @Mock UserUtils userUtils;
    @InjectMocks StudentSecurityService service;

    @Test
    void studentCannotManageEvenOwnLifecycle() {
        assertThat(service.canManageLifecycle(1)).isFalse();
        verifyNoInteractions(students);
    }

    @Test
    void unresolvedIdIsDeniedWithoutLookup() {
        assertThat(service.canManageLifecycle(null)).isFalse();
        assertThat(service.canViewStudent(null)).isFalse();
        verifyNoInteractions(students);
    }

    @Test
    void lifecycleLookupUsesTargetScopeAndNeverSelfPermission() {
        given(userUtils.getUserId()).willReturn(42);
        given(userUtils.hasRoleIncludingHierarchy("ROLE_instructor")).willReturn(true);
        given(students.findAccessible(1, 42, true, true, false)).willReturn(Optional.of(new Student()));
        assertThat(service.canManageLifecycle(1)).isTrue();
    }

    @Test
    void persistenceFailureDeniesBothNewGuards() {
        given(userUtils.getUserId()).willReturn(42);
        given(userUtils.hasRoleIncludingHierarchy("ROLE_instructor")).willReturn(true);
        given(students.findAccessible(1, 42, true, true, false))
                .willThrow(new DataAccessResourceFailureException("internal details"));
        given(students.findAccessible(1, 42, true, true, true))
                .willThrow(new DataAccessResourceFailureException("internal details"));
        assertThat(service.canManageLifecycle(1)).isFalse();
        assertThat(service.canViewStudent(1)).isFalse();
    }
}
