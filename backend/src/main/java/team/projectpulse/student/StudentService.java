package team.projectpulse.student;

import team.projectpulse.section.Section;
import team.projectpulse.section.SectionRepository;
import team.projectpulse.system.UserUtils;
import team.projectpulse.system.exception.ObjectNotFoundException;
import team.projectpulse.user.userinvitation.UserInvitationService;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;

@Service
@Transactional
public class StudentService {

    private final StudentRepository studentRepository;
    private final SectionRepository sectionRepository;
    private final UserInvitationService userInvitationService;
    private final PasswordEncoder passwordEncoder;
    private final UserUtils userUtils;


    public StudentService(StudentRepository studentRepository, SectionRepository sectionRepository, UserInvitationService userInvitationService, PasswordEncoder passwordEncoder, UserUtils userUtils) {
        this.studentRepository = studentRepository;
        this.sectionRepository = sectionRepository;
        this.userInvitationService = userInvitationService;
        this.passwordEncoder = passwordEncoder;
        this.userUtils = userUtils;
    }

    public Page<Student> findByCriteria(Map<String, String> searchCriteria, Pageable pageable) {
        Integer sectionId = this.userUtils.getUserSectionId();

        Specification<Student> spec = Specification.unrestricted(); // Start with an unrestricted specification, matching all objects.

        if (StringUtils.hasLength(searchCriteria.get("firstName"))) {
            spec = spec.and(StudentSpecs.containsFirstName(searchCriteria.get("firstName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("lastName"))) {
            spec = spec.and(StudentSpecs.containsLastName(searchCriteria.get("lastName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("email"))) {
            spec = spec.and(StudentSpecs.hasEmail(searchCriteria.get("email")));
        }

        if (StringUtils.hasLength(searchCriteria.get("teamName"))) {
            spec = spec.and(StudentSpecs.hasTeamName(searchCriteria.get("teamName")));
        }

        if (StringUtils.hasLength(searchCriteria.get("teamId"))) {
            spec = spec.and(StudentSpecs.hasTeamId(searchCriteria.get("teamId")));
        }

        if (StringUtils.hasLength(searchCriteria.get("sectionName"))) {
            spec = spec.and(StudentSpecs.hasSectionName(searchCriteria.get("sectionName")));
        }

        spec = spec.and(StudentSpecs.belongsToSection(sectionId)); // Only show students that belong to the default section of the instructor

        return this.studentRepository.findAll(spec, pageable);
    }

    public Student findStudentById(Integer studentId) {
        return this.studentRepository.findAccessible(studentId, this.userUtils.getUserId(),
                        this.userUtils.hasRoleIncludingHierarchy("ROLE_instructor"), true, true)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));
    }

    public List<Student> findStudentsByTeamId(Integer teamId) {
        return this.studentRepository.findByTeamTeamId(teamId);
    }

    public Student saveStudent(Student newStudent, Integer courseId, Integer sectionId, String registrationToken, String role) {
        // Validate user invitation
        this.userInvitationService.validateUserInvitation(newStudent.getEmail(), registrationToken, courseId, sectionId, role);

        // Password rule: At least 6 characters, contains at least one letter and one number.
        if (!newStudent.getPassword().matches("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,}$")) {
            throw new IllegalArgumentException("Password must contain at least one letter and one number, and at least 6 characters.");
        }

        // Add the student to the section
        Section section = this.sectionRepository.findById(sectionId)
                .orElseThrow(() -> new ObjectNotFoundException("section", sectionId));
        section.addStudent(newStudent);

        newStudent.setEnabled(true);
        newStudent.setRoles("student");
        newStudent.setPassword(this.passwordEncoder.encode(newStudent.getPassword()));

        Student savedStudent = this.studentRepository.save(newStudent);
        // Delete the user invitation after the student is saved.
        this.userInvitationService.deleteUserInvitation(newStudent.getEmail());
        return savedStudent;
    }

    public Student updateStudent(Integer studentId, Student update) {
        return updateStudent(studentId, update, false);
    }

    /** Body status is never a lifecycle instruction; restoration is explicit and atomic with profile saving. */
    public Student updateStudent(Integer studentId, Student update, boolean reactivate) {
        Integer callerId = this.userUtils.getUserId();
        boolean instructor = this.userUtils.hasRoleIncludingHierarchy("ROLE_instructor");
        requireLocked(studentId, callerId, instructor, reactivate, !reactivate);
        if (this.studentRepository.writeProfile(studentId, callerId, instructor, reactivate, !reactivate,
                update.getUsername(), update.getFirstName(), update.getLastName(), update.getEmail()) != 1) {
            throw new ObjectNotFoundException("student", studentId);
        }
        if (reactivate && this.studentRepository.writeStatus(studentId, callerId, instructor, true, false, true) != 1) {
            throw new ObjectNotFoundException("student", studentId);
        }
        // Bulk writes clear managed state, and a locking read sees the current committed row, not an old snapshot.
        return requireLocked(studentId, callerId, instructor, reactivate, !reactivate);
    }

    public Student deactivateStudent(Integer studentId) {
        Integer callerId = this.userUtils.getUserId();
        boolean instructor = this.userUtils.hasRoleIncludingHierarchy("ROLE_instructor");
        requireLocked(studentId, callerId, instructor, true, false);
        if (this.studentRepository.writeStatus(studentId, callerId, instructor, true, false, false) != 1) {
            throw new ObjectNotFoundException("student", studentId);
        }
        return requireLocked(studentId, callerId, instructor, true, false);
    }

    private Student requireLocked(Integer studentId, Integer callerId, boolean instructor, boolean allowOwner, boolean allowSelf) {
        return this.studentRepository.lockAccessible(studentId, callerId, instructor, allowOwner, allowSelf)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));
    }

    public void deleteStudent(Integer studentId) {
        this.studentRepository.findById(studentId)
                .orElseThrow(() -> new ObjectNotFoundException("student", studentId));
        this.studentRepository.deleteById(studentId);
    }

}
