package it.polito.ai.virtualLabs.services;

import com.opencsv.bean.CsvToBean;
import com.opencsv.bean.CsvToBeanBuilder;
import it.polito.ai.virtualLabs.dtos.*;
import it.polito.ai.virtualLabs.entities.*;
import it.polito.ai.virtualLabs.repositories.*;
import it.polito.ai.virtualLabs.services.exceptions.course.CourseNotEnabledException;
import it.polito.ai.virtualLabs.services.exceptions.course.CourseNotFoundException;
import it.polito.ai.virtualLabs.services.exceptions.file.ParsingFileException;
import it.polito.ai.virtualLabs.services.exceptions.student.StudentAlreadyTeamedUpException;
import it.polito.ai.virtualLabs.services.exceptions.student.StudentNotEnrolledException;
import it.polito.ai.virtualLabs.services.exceptions.student.StudentNotFoundException;
import it.polito.ai.virtualLabs.services.exceptions.team.*;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.transaction.Transactional;
import java.io.Reader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;


@Service
@Transactional
public class TeamServiceImpl implements TeamService {

    private static final int PROPOSAL_EXPIRATION_DAYS = 3;

    // Queste sono da esempio per usarle dopo
    // @PreAuthorize("hasAnyRole('ROLE_PROFESSOR','ROLE_ADMIN')")
    // @PreAuthorize("hasRole('ROLE_ADMIN')")

    @Autowired
    AssignmentRepository assignmentRepository;
    @Autowired
    CourseRepository courseRepository;
    @Autowired
    ReportRepository reportRepository;
    @Autowired
    TeamProposalRepository teamProposalRepository;
    @Autowired
    TeamRepository teamRepository;
    @Autowired
    UserRepository userRepository;
    @Autowired
    UserRepository studentRepository;
    @Autowired
    VersionRepository versionRepository;
    @Autowired
    VmModelRepository vmModelRepository;
    @Autowired
    VmRepository vmRepository;


    @Autowired
    TeamService teamService;
    @Autowired
    NotificationService notificationService;
    @Autowired
    ModelMapper modelMapper;

    @Override
    public boolean addCourse(CourseDTO course) {
        if(courseRepository.existsById(course.getName()))
            return false;
        Course c = modelMapper.map(course, Course.class);
        courseRepository.saveAndFlush(c);
        return true;
    }

    @Override
    public Optional<CourseDTO> getCourse(String name) {
        if (!courseRepository.existsById(name))
            return Optional.empty();
        return courseRepository.findById(name)
                .map(c -> modelMapper.map(c, CourseDTO.class));
    }

    @Override
    public List<CourseDTO> getAllCourses() {
        return courseRepository.findAll()
                .stream()
                .map(c -> modelMapper.map(c, CourseDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public boolean addStudent(StudentDTO student) {
        if(userRepository.existsById(student.getId()))
            return false;
        Student s = modelMapper.map(student, Student.class);
        userRepository.saveAndFlush(s);
        return true;
    }

    @Override
    public Optional<StudentDTO> getStudent(String studentId) {

        if (!userRepository.existsById(studentId))
            return Optional.empty();
        return userRepository.findStudentById(studentId)
                .map(s -> modelMapper.map(s, StudentDTO.class));
    }

    @Override
    public List<StudentDTO> getAllStudents() {
        return userRepository.findAllStudents()
                .stream()
                .map(s -> modelMapper.map(s, StudentDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public boolean addProfessor(ProfessorDTO professor) {
        if(userRepository.existsById(professor.getId()))
            return false;
        Professor p = modelMapper.map(professor, Professor.class);
        userRepository.saveAndFlush(p);
        return true;
    }

    @Override
    public Optional<ProfessorDTO> getProfessor(String professorId) {
        if (!userRepository.existsById(professorId))
            return Optional.empty();
        return userRepository.findProfessorById(professorId)
                .map(p -> modelMapper.map(p, ProfessorDTO.class));
    }

    @Override
    public List<ProfessorDTO> getAllProfessors() {
        return userRepository.findAllProfessors()
                .stream()
                .map(p -> modelMapper.map(p, ProfessorDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public TeamDTO acceptTeamProposal(Long teamProposalId) {
        if(!teamProposalRepository.existsById(teamProposalId))
            throw new TeamProposalNotFoundException("The proposal with id '"+ teamProposalId +"' was not found");

        TeamProposal teamProposal = teamProposalRepository.getOne(teamProposalId);

        if(teamRepository.existsByNameAndCourseName(teamProposal.getTeamName(), teamProposal.getCourse().getName()))
            throw new TeamProposalAlreadyAcceptedException("The proposal with id '"+ teamProposalId +"' was already accepted");

        String courseName = teamProposal.getCourse().getName();
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");

        Course course = courseRepository.getOne(courseName);
        if(!course.isEnabled())
            throw new CourseNotEnabledException("The course named '" + courseName + "' is not enabled");

        List<String> distinctMembersIds = teamProposal.getStudents().stream().map(Student::getId).distinct().collect(Collectors.toList());
        if(distinctMembersIds.size() < course.getMinTeamSize() && distinctMembersIds.size() > course.getMaxTeamSize())
            throw new TeamConstraintsNotSatisfiedException("The team named '" + teamProposal.getTeamName() + "' does not meet the cardinality constraints");

        List<Student> studentsToAdd = new ArrayList<>();
        for(String memberId : distinctMembersIds) {
            if(!userRepository.studentExistsById(memberId))
                throw new StudentNotFoundException("The student with id '" + memberId + "' was not found");

            Student student = userRepository.getStudentById(memberId);
            if(!student.getCourses().contains(course))
                throw new StudentNotEnrolledException("The student with id '" + memberId + "' is not enrolled to the course named '" + courseName + "'");

            List<Team> studentTeams = student.getTeams();
            for(Team t : studentTeams) {
                if(t.getCourse().getName().equals(courseName))
                    throw new StudentAlreadyTeamedUpException("The student with id '" + memberId + "' is already part of the group named '" + t.getName() + "'");
            }
            studentsToAdd.add(student); //this will be part of the team (if all the controls are verified)
        }
        // Create new team
        Team team = new Team();
        team.setName(teamProposal.getTeamName());
        team.setCourse(course);

        teamProposal.setStatus(TeamProposal.TeamProposalStatus.CONFIRMED);

        teamRepository.saveAndFlush(team);
        for(Student s : studentsToAdd) {
            s.addToTeam(team);
        }

        return modelMapper.map(team, TeamDTO.class);
    }

    @Override
    public boolean rejectTeamProposal(Long teamProposalId) {
        if(!teamProposalRepository.existsById(teamProposalId))
            throw new TeamProposalNotFoundException("The proposal with id '"+ teamProposalId +"' was not found");

        TeamProposal teamProposal = teamProposalRepository.getOne(teamProposalId);
        if(teamProposal.getStatus() == TeamProposal.TeamProposalStatus.REJECTED)
            return false;

        teamProposal.setStatus(TeamProposal.TeamProposalStatus.REJECTED);
        return true;
    }

    @Override
    public Optional<TeamDTO> getTeam(String teamName, String courseName) {
        if (!teamRepository.existsByNameAndCourseName(teamName, courseName))
            return Optional.empty();
        return teamRepository.findByNameAndCourseName(teamName, courseName)
                .map(t -> modelMapper.map(t, TeamDTO.class));
    }

    @Override
    public List<StudentDTO> getEnrolledStudents(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");

        return courseRepository.getOne(courseName).getStudents()
                .stream()
                .map(s -> modelMapper.map(s, StudentDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public boolean addStudentToCourse(String studentId, String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");
        if(!userRepository.studentExistsById(studentId))
            throw new StudentNotFoundException("The student with id '" + studentId + "' was not found");

        Course course = courseRepository.getOne(courseName);
        Optional<Student> student = course.getStudents()
                .stream()
                .filter(s -> s.getId().equals(studentId))
                .findFirst();
        if(student.isPresent())
            return false;
        else {
            Student s = userRepository.getStudentById(studentId);
            course.addStudent(s);
            return true;
        }
    }

    @Override
    public boolean addProfessorToCourse(String professorId, String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' non è stato trovato");
        if(!userRepository.professorExistsById(professorId))
            throw new StudentNotFoundException("The professor with id '" + professorId + "' was not found");

        Course course = courseRepository.getOne(courseName);
        Optional<Professor> professor = course.getProfessors()
                .stream()
                .filter(p -> p.getId().equals(professorId))
                .findFirst();
        if(professor.isPresent())
            return false;
        else {
            Professor p = userRepository.getProfessorById(professorId);
            course.addProfessor(p);
            return true;
        }
    }

    @Override
    public List<ProfessorDTO> getProfessorsForCourse(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");

        return courseRepository.getOne(courseName).getProfessors()
                .stream()
                .map(p -> modelMapper.map(p, ProfessorDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public void enableCourse(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");
        Course c = courseRepository.getOne(courseName);
        c.setEnabled(true);
    }

    @Override
    public void disableCourse(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");
        Course c = courseRepository.getOne(courseName);
        c.setEnabled(false);
    }

    @Override
    public List<Boolean> addAllStudents(List<StudentDTO> students) {
        List<Boolean> retList = new ArrayList<>();
        for(StudentDTO s : students) {
            retList.add(addStudent(s));
        }
        return retList;
    }

    @Override
    public List<Boolean> addAllProfessors(List<ProfessorDTO> professors) {
        List<Boolean> retList = new ArrayList<>();
        for(ProfessorDTO p : professors) {
            retList.add(addProfessor(p));
        }
        return retList;
    }

    @Override
    public List<Boolean> enrollAllStudents(List<String> studentIds, String courseName) {
        List<Boolean> retList = new ArrayList<>();
        for(String id : studentIds) {
            retList.add(addStudentToCourse(id, courseName));
        }
        return retList;
    }

    //TODO: da testare
    @Override
    public List<Boolean> addAndEnroll(Reader r, String courseName) {
        List<StudentDTO> students;
        try {
            // create csv bean reader
            CsvToBean<StudentDTO> csvToBean = new CsvToBeanBuilder(r)
                    .withType(UserDTO.class)
                    .withIgnoreLeadingWhiteSpace(true)
                    .build();
            // convert `CsvToBean` object to list of students
            students = csvToBean.parse();
        } catch(Exception ex) {
           throw new ParsingFileException("Error in parsing file");
        }

        List<Boolean> retList = new ArrayList<Boolean>();
        for(StudentDTO s : students) {
            boolean added = addStudent(s);
            boolean enrolled = addStudentToCourse(s.getId(), courseName);
            retList.add(added || enrolled);
        }
        return retList;
    }

    @Override
    public List<CourseDTO> getCoursesForStudent(String studentId) {
        if (!userRepository.studentExistsById(studentId))
            throw new StudentNotFoundException("The student with id '" + studentId + "' was not found");
        /*
        UserDetails userDetails = (UserDetails) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        userDetails.getAuthorities().forEach(role -> {
            if(role.getAuthority().equals("ROLE_STUDENT")) {
                Optional<User> user = userRepository.findByUsername(userDetails.getUsername());
                if(user.isPresent()) {
                    String id = user.get().getId();
                    if(!studentId.equals(id)) {
                        throw new StudentPrivacyException("The student with id '" + studentId + "' does not have permission to view this info");
                    }
                }
            }
        });*/
        Student student = userRepository.getStudentById(studentId);
        return student.getCourses()
                .stream()
                .map(c -> modelMapper.map(c, CourseDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<CourseDTO> getCoursesForProfessor(String professorId) {
        if (!userRepository.professorExistsById(professorId))
            throw new StudentNotFoundException("The professor with id '" + professorId + "' was not found");

        Professor professor = userRepository.getProfessorById(professorId);
        return professor.getCourses()
                .stream()
                .map(c -> modelMapper.map(c, CourseDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<TeamDTO> getTeamsForStudent(String studentId) {
        if(!userRepository.existsById(studentId))
            throw new StudentNotFoundException("The student with id '" + studentId + "' was not found");
        /*
        UserDetails userDetails = (UserDetails)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        userDetails.getAuthorities().forEach(role -> {
            if(role.getAuthority().equals("ROLE_STUDENT")) {
                Optional<User> user = userRepository.findByUsername(userDetails.getUsername());
                if(user.isPresent()) {
                    String id = user.get().getId();
                    if(!studentId.equals(id)) {
                        throw new StudentPrivacyException("The student with id '" + studentId + "' does not have permission to view this info");
                    }
                }
            }
        });*/
        Student student = userRepository.getStudentById(studentId);
        return student.getTeams()
                .stream()
                .map(t -> modelMapper.map(t, TeamDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<StudentDTO> getTeamMembers(Long teamId) {
        if(!teamRepository.existsById(teamId))
            throw new TeamNotFoundException("The team with id '" + teamId + "' was not found");

        return teamRepository
                .getOne(teamId)
                .getStudents()
                .stream()
                .map(s -> modelMapper.map(s, StudentDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public TeamProposalDTO proposeTeam(String courseName, String teamName, List<String> memberIds) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");

        Optional<TeamProposal> oldProposal = teamProposalRepository.findByTeamNameAndCourseName(teamName, courseName);
        if(oldProposal.isPresent() && oldProposal.get().getStatus() != TeamProposal.TeamProposalStatus.REJECTED)
            throw new TeamAlreadyProposedException("The team '" + teamName + "' for the course named '" + courseName + "' has already a request in progress or accepted");

        Course course = courseRepository.getOne(courseName);
        if(!course.isEnabled())
            throw new CourseNotEnabledException("The course named '" + courseName + "' is not enabled");

        List<String> distinctMembersIds = memberIds.stream().distinct().collect(Collectors.toList());
        if(distinctMembersIds.size() < course.getMinTeamSize() && distinctMembersIds.size() > course.getMaxTeamSize())
            throw new TeamConstraintsNotSatisfiedException("The team '" + teamName + "' does not respect cardinality constraints");

        List<Student> students = new ArrayList<>();
        for(String memberId : distinctMembersIds) {
            if(!userRepository.existsById(memberId))
                throw new StudentNotFoundException("The student with id '" + memberId + "' was not found");

            Student student = userRepository.getStudentById(memberId);
            if(!student.getCourses().contains(course))
                throw new StudentNotEnrolledException("The student with id '" + memberId + "' is not enrolled to the course named '" + "' " + courseName);

            List<Team> studentTeams = student.getTeams();
            for(Team t : studentTeams) {
                if(t.getCourse().getName().equals(courseName))
                    throw new StudentAlreadyTeamedUpException("The student with id '" + memberId + "' is already part of the group named '" + t.getName() + "'");
            }
            students.add(student); //this will be part of the team (if all the controls are verified)
        }
        // Create new team proposal
        TeamProposal proposal = new TeamProposal();
        proposal.setStatus(TeamProposal.TeamProposalStatus.PENDING);
        proposal.setCourse(course);
        proposal.setTeamName(teamName);
        proposal.setExpiryDate(LocalDateTime.now().plusDays(PROPOSAL_EXPIRATION_DAYS));

        teamProposalRepository.saveAndFlush(proposal);
        for(Student s : students) {
            s.addTeamProposal(proposal);
            proposal.addToken(UUID.randomUUID().toString());
        }

        //TODO: far partire le email da qui (chiamare la notifyTeam)

        return modelMapper.map(proposal, TeamProposalDTO.class);
    }

    @Override
    public Optional<TeamProposalDTO> getTeamProposal(Long teamProposalId) {
        if(!teamProposalRepository.existsById(teamProposalId))
            return Optional.empty();
        return teamProposalRepository.findById(teamProposalId)
                .map(t -> modelMapper.map(t, TeamProposalDTO.class));

    }

    @Override
    public List<TeamProposalDTO> getPendingTeamProposalForCourse(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");
        return teamProposalRepository.findAllByCourseNameAndStatus(courseName, TeamProposal.TeamProposalStatus.PENDING)
                .stream()
                .map(tp -> modelMapper.map(tp, TeamProposalDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<TeamProposalDTO> getTeamProposalsForCourse(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");

        return courseRepository
                .getOne(courseName)
                .getTeamProposals()
                .stream()
                .map(tp -> modelMapper.map(tp, TeamProposalDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<TeamProposalDTO> getTeamProposalsForStudent(String studentId) {
        if(!userRepository.studentExistsById(studentId))
            throw new StudentNotFoundException("The student with id '" + studentId + "' was not found");

        return userRepository
                .getStudentById(studentId)
                .getTeamProposals()
                .stream()
                .map(tp -> modelMapper.map(tp, TeamProposalDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<TeamDTO> getTeamsForCourse(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");
        /*
        UserDetails userDetails = (UserDetails)SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        userDetails.getAuthorities().forEach(role -> {
            if(role.getAuthority().equals("ROLE_STUDENT")) {
                Optional<User> user = userRepository.findByUsername(userDetails.getUsername());
                user.ifPresent(value -> {
                    Student student = (Student)value;
                    student.getCourses().forEach(course -> {
                        if (!courseName.equals(course.getName())) {
                            throw new StudentPrivacyException("The student does not have permission to view the information relating to the course named " + courseName);
                        }
                    });
                });
            }
        });*/
        return courseRepository
                .getOne(courseName)
                .getTeams()
                .stream()
                .map(t -> modelMapper.map(t, TeamDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<StudentDTO> getStudentsInTeams(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");
        return courseRepository
                .getStudentsInTeams(courseName)
                .stream()
                .map(s -> modelMapper.map(s, StudentDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public List<StudentDTO> getAvailableStudents(String courseName) {
        if(!courseRepository.existsById(courseName))
            throw new CourseNotFoundException("The course named '" + courseName + "' was not found");
        return courseRepository
                .getStudentsNotInTeams(courseName)
                .stream()
                .map(s -> modelMapper.map(s, StudentDTO.class))
                .collect(Collectors.toList());
    }

    @Override
    public void changeTeamProposalStatus(Long teamProposalId, TeamProposal.TeamProposalStatus newStatus) {
        if(!teamProposalRepository.existsById(teamProposalId))
            throw new TeamNotFoundException("The team proposal with id " + teamProposalId + " does not exist");
        teamProposalRepository.getOne(teamProposalId).setStatus(newStatus);
    }

    @Override
    public void deleteTeam(Long teamId) {
        if(!teamRepository.existsById(teamId))
            throw new TeamNotFoundException("The team with id " + teamId + " does not exist");
        teamRepository.deleteById(teamId);
        teamRepository.flush();
    }
}
