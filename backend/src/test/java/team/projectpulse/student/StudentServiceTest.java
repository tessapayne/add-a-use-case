package team.projectpulse.student;

import org.junit.jupiter.api.AfterEach;
import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.user.userinvitation.UserInvitationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StudentServiceTest {

    @Mock
    StudentRepository studentRepository;
    @Mock
    SectionRepository sectionRepository;
    @Mock
    UserInvitationService userInvitationService;
    @Mock
    PasswordEncoder passwordEncoder;
    @Mock
    UserUtils userUtils;

    @InjectMocks
    private StudentService studentService;

    List<Student> students;

    @BeforeEach
    void setUp() {
        // Create students
        Student john = new Student("john", "John", "Smith", "j.smith@abc.edu", "123456", true, "student");
        Student eric = new Student("eric", "Eric", "Hudson", "e.hudson@abc.edu", "123456", true, "student");
        Student jerry = new Student("jerry", "Jerry", "Moon", "j.moon@abc.edu", "123456", true, "student");
        Student woody = new Student("woody", "Woody", "Allen", "w.allen@abc.edu", "123456", true, "student");
        Student amanda = new Student("amanda", "Amanda", "Wagner", "a.wagner@abc.edu", "123456", true, "student");
        Student cora = new Student("cora", "Cora", "Manning", "c.manning@abc.edu", "123456", true, "student");
        Student agustin = new Student("agustin", "Agustin", "Freeman", "a.freeman@abc.edu", "123456", true, "student");
        Student mavis = new Student("mavis", "Mavis", "Huber", "m.huber@abc.edu", "123456", true, "student");
        Student mary = new Student("mary", "Mary", "Vargas", "m.vargas@abc.edu", "123456", true, "student");
        Student rosendo = new Student("rosendo", "Rosendo", "Maxwell", "r.maxwell@abc.edu", "123456", true, "student");

        this.students = List.of(john, eric, jerry, woody, amanda, cora, agustin, mavis, mary, rosendo);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext(); // Clear the security context after each test to avoid interference between tests
    }

    @Test
    void testFindByCriteria() {
        // Given
        Map<String, String> searchCriteria = Map.of("firstName", "john");
        PageImpl expectedPage = new PageImpl(List.of(this.students.get(0)));

        given(this.userUtils.getUserSectionId()).willReturn(2);
        given(this.studentRepository.findAll(any(Specification.class), any(Pageable.class))).willReturn(expectedPage);
        // When
        Page<Student> result = this.studentService.findByCriteria(searchCriteria, PageRequest.of(0, 20));

        // Then
        assertThat(result.getContent().size()).isEqualTo(1);
    }

    @Test
    void testFindStudentById() {
        // Given
        Student john = this.students.get(0);
        given(this.studentRepository.findAccessible(1, 0, false, true, true)).willReturn(Optional.of(john));

        // When
        Student result = this.studentService.findStudentById(1);

        // Then
        assertThat(result.getFirstName()).isEqualTo("John");
        assertThat(result.getLastName()).isEqualTo("Smith");
    }

    @Test
    void testSaveStudent() {
        // Given
        Student jan = new Student("jan", "Jan", "Mckinney", "j.mckinney@abc.edu", "Abc123456", true, "student");
        doNothing().when(this.userInvitationService).validateUserInvitation(anyString(), anyString(), anyInt(), anyInt(), anyString());
        given(this.sectionRepository.findById(1)).willReturn(Optional.of(new Section()));
        given(this.passwordEncoder.encode(anyString())).willReturn("Encoded Password");
        given(this.studentRepository.save(jan)).willReturn(jan);

        // When
        Student result = this.studentService.saveStudent(jan, 1, 1, "token", "student");

        // Then
        assertThat(result.getFirstName()).isEqualTo("Jan");
        assertThat(result.getLastName()).isEqualTo("Mckinney");
        assertThat(result.getEmail()).isEqualTo("j.mckinney@abc.edu");
        assertThat(result.getRoles()).isEqualTo("student");
        assertThat(result.getSection()).isNotNull();
        assertThat(result.isEnabled()).isEqualTo(true);
    }

    @Test
    void testStudentUpdateOwnInfo() {
        Student current = this.students.get(0);
        Student update = new Student("john", "Changed", "Smith", "new@abc.edu", "", false, "admin");
        given(this.userUtils.getUserId()).willReturn(1);
        given(this.studentRepository.lockAccessible(1, 1, false, false, true)).willReturn(Optional.of(current));
        given(this.studentRepository.writeProfile(1, 1, false, false, true,
                "john", "Changed", "Smith", "new@abc.edu")).willReturn(1);

        assertThat(this.studentService.updateStudent(1, update)).isSameAs(current);
        verify(this.studentRepository, never()).writeStatus(any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
        verify(this.studentRepository, never()).save(any());
    }

    @Test
    void testDeactivateRepeatStillChecksScopeAndWritesOnlyStatus() {
        Student inactive = this.students.get(0);
        inactive.setEnabled(false);
        given(this.userUtils.getUserId()).willReturn(42);
        given(this.userUtils.hasRoleIncludingHierarchy("ROLE_instructor")).willReturn(true);
        given(this.studentRepository.lockAccessible(1, 42, true, true, false)).willReturn(Optional.of(inactive));
        given(this.studentRepository.writeStatus(1, 42, true, true, false, false)).willReturn(1);

        assertThat(this.studentService.deactivateStudent(1).isEnabled()).isFalse();
        verify(this.studentRepository, never()).save(any());
        assertThat(inactive.getFirstName()).isEqualTo("John");
    }

    @Test
    void testScopeLossAtMutationReturnsNotFound() {
        given(this.studentRepository.lockAccessible(1, 0, false, true, false))
                .willReturn(Optional.of(this.students.get(0)));
        // The mutation's own predicate no longer finds an accessible target.
        given(this.studentRepository.writeStatus(1, 0, false, true, false, false)).willReturn(0);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.studentService.deactivateStudent(1))
                .isInstanceOf(team.projectpulse.system.exception.ObjectNotFoundException.class);
        verify(this.studentRepository, never()).save(any());
    }

    @Test
    void testScopeLossBeforeLookupDoesNotWrite() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> this.studentService.deactivateStudent(1))
                .isInstanceOf(team.projectpulse.system.exception.ObjectNotFoundException.class);
        verify(this.studentRepository, never()).writeStatus(any(), any(), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    }

    @Test
    void testDeleteStudent() {
        // Given
        given(this.studentRepository.findById(1)).willReturn(Optional.of(this.students.get(0)));
        doNothing().when(this.studentRepository).deleteById(1);

        // When
        this.studentService.deleteStudent(1);

        // Then
        verify(this.studentRepository, times(1)).deleteById(1);
    }

}