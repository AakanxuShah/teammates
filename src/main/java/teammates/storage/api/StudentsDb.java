package teammates.storage.api;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.logging.Logger;

import javax.jdo.JDOHelper;
import javax.jdo.Query;

import teammates.common.datatransfer.EntityAttributes;
import teammates.common.datatransfer.InstructorAttributes;
import teammates.common.datatransfer.StudentAttributes;
import teammates.common.datatransfer.StudentSearchResultBundle;
import teammates.common.exception.EntityAlreadyExistsException;
import teammates.common.exception.EntityDoesNotExistException;
import teammates.common.exception.InvalidParametersException;
import teammates.common.util.Assumption;
import teammates.common.util.Config;
import teammates.common.util.Const;
import teammates.common.util.StringHelper;
import teammates.common.util.ThreadHelper;
import teammates.common.util.Utils;
import teammates.storage.entity.CourseStudent;
import teammates.storage.entity.Student;
import teammates.storage.search.StudentSearchDocument;
import teammates.storage.search.StudentSearchQuery;

import com.google.appengine.api.datastore.KeyFactory;
import com.google.appengine.api.search.Results;
import com.google.appengine.api.search.ScoredDocument;

/**
 * Handles CRUD Operations for student entities.
 * The API uses data transfer classes (i.e. *Attributes) instead of persistable classes.
 * 
 */
public class StudentsDb extends EntitiesDb {

    public static final String ERROR_UPDATE_EMAIL_ALREADY_USED = "Trying to update to an email that is already used by: ";
    
    private static final Logger log = Utils.getLogger();

    public void putDocument(StudentAttributes student){
        putDocument(Const.SearchIndex.STUDENT, new StudentSearchDocument(student));
    }
    
    /**
     * Search for students
     * @return {@link StudentSearchResultBundle}
     */
    public StudentSearchResultBundle search(String queryString, List<InstructorAttributes> instructors,
                                            String cursorString) {
        if(queryString.trim().isEmpty())
            return new StudentSearchResultBundle();
        
        Results<ScoredDocument> results = searchDocuments(Const.SearchIndex.STUDENT, 
                new StudentSearchQuery(instructors, queryString, cursorString));
        
        return new StudentSearchResultBundle().fromResults(results, instructors);
    }
    
    
    
    
    /**
     * This method should be used by admin only since the searching does not restrict the 
     * visibility according to the logged-in user's google ID. This is used by amdin to
     * search students in the whole system.
     * @param queryString
     * @param cursorString
     * @return null if no result found
     */ 
    public StudentSearchResultBundle searchStudentsInWholeSystem(String queryString, String cursorString){
        if(queryString.trim().isEmpty())
            return new StudentSearchResultBundle();
        
        Results<ScoredDocument> results = searchDocuments(Const.SearchIndex.STUDENT, 
                new StudentSearchQuery(queryString, cursorString));
        
        return new StudentSearchResultBundle().getStudentsfromResults(results);
    }
    

    public void deleteDocument(StudentAttributes studentToDelete){
        
        if(studentToDelete.key == null){
            StudentAttributes student = getStudentForEmail(studentToDelete.course, studentToDelete.email);
            if (student != null) {
                deleteDocument(Const.SearchIndex.STUDENT, student.key);
            }
        } else {
            deleteDocument(Const.SearchIndex.STUDENT, studentToDelete.key);
        }
    }
    
    /**
     * Create students' records without searchability
     * This function is currently used in testing process only
     * @param studentsToAdd
     * @throws InvalidParametersException
     */
    public void createStudentsWithoutSearchability(Collection<StudentAttributes> studentsToAdd) throws InvalidParametersException{
        
        List<EntityAttributes> studentsToUpdate = createEntities(studentsToAdd);
        for(EntityAttributes entity : studentsToUpdate){
            StudentAttributes student = (StudentAttributes) entity;
            try {          
                updateStudentWithoutSearchability(student.course, student.email, student.name, student.team, student.section, student.email, student.googleId, student.comments);
            } catch (EntityDoesNotExistException e) {
             // This situation is not tested as replicating such a situation is 
             // difficult during testing
                Assumption.fail("Entity found be already existing and not existing simultaneously");
            }
        }
    }
    
    public void createStudent(StudentAttributes student)
            throws InvalidParametersException, EntityAlreadyExistsException {
        
        createStudent(student, true);
    }

    public void createStudentWithoutDocument(StudentAttributes student)
            throws InvalidParametersException, EntityAlreadyExistsException {
        createStudent(student, false);
    }

    public void createStudent(StudentAttributes student, boolean hasDocument)
            throws InvalidParametersException, EntityAlreadyExistsException {
        StudentAttributes createdStudent = new StudentAttributes((Student)createEntity(student));
        if (hasDocument) {
            putDocument(createdStudent);
        }
    }

    /**
     * Preconditions: <br>
     * * All parameters are non-null.
     * 
     * @return The data for Student with the courseId and email. Returns null if
     *         there is no such student.
     */
    public StudentAttributes getStudentForEmail(String courseId, String email) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, email);
    
        
        // Return CourseStudent if it exists. Otherwise, fall back on Student.
        CourseStudent cs = getCourseStudentEntityForEmail(courseId, email);
        if (cs != null) {
            return new StudentAttributes(cs);
        }
        
        
        Student s = getStudentEntityForEmail(courseId, email);

        if (s == null) {
            log.info("Trying to get non-existent Student: " + courseId + "/" + email);
            return null;
        }
    
        return new StudentAttributes(s);
    }

    /**
     * Preconditions: 
     * <br> * All parameters are non-null.
     * @return null if no such student is found. 
     */
    public StudentAttributes getStudentForGoogleId(String courseId, String googleId) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, googleId);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        
        // Return CourseStudent if it exists. Otherwise, fall back on Student.
        Query q = getPM().newQuery(CourseStudent.class);
        q.declareParameters("String googleIdParam, String courseIdParam");
        q.setFilter("ID == googleIdParam && courseID == courseIdParam");
        
        @SuppressWarnings("unchecked")
        List<CourseStudent> courseStudentList = (List<CourseStudent>)q.execute(googleId, courseId);
        
        if (courseStudentList.isEmpty() || JDOHelper.isDeleted(courseStudentList.get(0))) {
            // Don't return yet, look up Student.
        } else {
            return new StudentAttributes(courseStudentList.get(0));
        }
        
        
        

        q = getPM().newQuery(Student.class);
        q.declareParameters("String googleIdParam, String courseIdParam");
        q.setFilter("ID == googleIdParam && courseID == courseIdParam");
        
        @SuppressWarnings("unchecked")
        List<Student> studentList = (List<Student>)q.execute(googleId, courseId);
        
        if (studentList.isEmpty() || JDOHelper.isDeleted(studentList.get(0))) {
            return null;
        } else {
            return new StudentAttributes(studentList.get(0));
        }
    }
    
    /**
     * Works for both encrypted keys and unencrypted keys 
     *   (sent out before we started encrypting keys). <br>
     * Preconditions: <br>
     * * All parameters are non-null.
     * @return null if no matching student.
     */
    public StudentAttributes getStudentForRegistrationKey(String registrationKey){
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, registrationKey);
        StudentAttributes studentAttributes;
        registrationKey = registrationKey.trim();
        String originalKey = registrationKey;
        
        
        try {

            // CourseStudent
            registrationKey = StringHelper.decrypt(registrationKey);
            CourseStudent courseStudent = getCourseStudentEntityForRegistrationKey(registrationKey);
            
            if (courseStudent != null) {
                return new StudentAttributes(courseStudent);
            }
            

            // Student
            // First, try to retrieve the student by assuming the given registrationKey key is encrypted
            registrationKey = StringHelper.decrypt(registrationKey);
            Student student = getPM().getObjectById(Student.class,
                    KeyFactory.stringToKey(registrationKey));
            studentAttributes = new StudentAttributes(student); 
        } catch (Exception e) {
            try {
                //Failing that, we try to retrieve assuming the given registrationKey is unencrypted 
                //  (early versions of the system sent unencrypted keys).
                //TODO: This branch can be removed after Dec 2013
                Student student = getPM().getObjectById(Student.class,
                        KeyFactory.stringToKey(originalKey));
                studentAttributes = new StudentAttributes(student);
            } catch (Exception e2) {
                //Failing both, we assume there is no such student
                studentAttributes = null;
            }
        }
        
        return studentAttributes;
    }


    /**
     * Preconditions: 
     * <br> * All parameters are non-null.
     * @return an empty list if no such students are found.
     */
    public List<StudentAttributes> getStudentsForGoogleId(String googleId) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, googleId);
        
        List<StudentAttributes> studentDataList = new ArrayList<StudentAttributes>();
        
        List<CourseStudent> courseStudentList = getCourseStudentEntitiesForGoogleId(googleId);
        for (CourseStudent student : courseStudentList) {
            if (!JDOHelper.isDeleted(student)) {
                studentDataList.add(new StudentAttributes(student));
            }
        }
        
        List<Student> studentList = getStudentEntitiesForGoogleId(googleId);
        for (Student student : studentList) {
            // Check if StudentAttributes is already in list due to CourseStudent
            if (!JDOHelper.isDeleted(student)) {
                StudentAttributes s = new StudentAttributes(student);
                if (!isListContainsSameStudentAttributes(studentDataList, s)) {
                    studentDataList.add(s);
                }
            }
        }
    
        return studentDataList;
    }

    /**
     * Preconditions: <br>
     *  * All parameters are non-null.
     * @return an empty list if no students in the course.
     */
    public List<StudentAttributes> getStudentsForCourse(String courseId) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        
        List<StudentAttributes> studentDataList = new ArrayList<StudentAttributes>();
        
        List<CourseStudent> courseStudentList = getCourseStudentEntitiesForCourse(courseId);
        for (CourseStudent student : courseStudentList) {
            if (!JDOHelper.isDeleted(student)) {
                studentDataList.add(new StudentAttributes(student));
            }
        }
        
        List<Student> studentList = getStudentEntitiesForCourse(courseId);
        for (Student student : studentList) {
            // Check if StudentAttributes is already in list due to CourseStudent
            if (!JDOHelper.isDeleted(student)) {
                StudentAttributes s = new StudentAttributes(student);
                if (!isListContainsSameStudentAttributes(studentDataList, s)) {
                    studentDataList.add(s);
                }
            }
        }
    
        return studentDataList;
    }
    
    /**
     * Preconditions: <br>
     *  * All parameters are non-null.
     * @return an empty list if no students in the course.
     */
    public List<StudentAttributes> getStudentsForTeam(String teamName, String courseId) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, teamName);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        
        List<StudentAttributes> studentDataList = new ArrayList<StudentAttributes>();
        List<CourseStudent> courseStudentList = getCourseStudentEntitiesForTeam(teamName, courseId);
        
        //TODO: See if we can use a generic method to convert a list of entities to a list of attributes.
        //  e.g., convertToAttributes(entityList, new ArrayList<StudentAttributes>())
        for (CourseStudent student : courseStudentList) {
            if (!JDOHelper.isDeleted(student)) {
                studentDataList.add(new StudentAttributes(student));
            }
        }
        
        List<Student> studentList = getStudentEntitiesForTeam(teamName, courseId);
        for (Student student : studentList) {
            // Check if StudentAttributes is already in list due to CourseStudent
            if (!JDOHelper.isDeleted(student)) {
                StudentAttributes s = new StudentAttributes(student);
                if (!isListContainsSameStudentAttributes(studentDataList, s)) {
                    studentDataList.add(s);
                }
            }
        }
    
        return studentDataList;
    }

    /**
     *  Preconditions: <br>
     *  All parameters are non-null
     *  @return an empty list if no students in this section
     */
    public List<StudentAttributes> getStudentsForSection(String sectionName, String courseId) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, sectionName);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);

        List<StudentAttributes> studentDataList = new ArrayList<StudentAttributes>();
        
        List<CourseStudent> courseStudentList = getCourseStudentEntitiesForSection(sectionName, courseId);
        
        for (CourseStudent student : courseStudentList) {
            if (!JDOHelper.isDeleted(student)) {
                studentDataList.add(new StudentAttributes(student));
            }
        }
        
        List<Student> studentList = getStudentEntitiesForSection(sectionName, courseId);
        for (Student student : studentList) {
            // Check if StudentAttributes is already in list due to CourseStudent
            if (!JDOHelper.isDeleted(student)) {
                StudentAttributes s = new StudentAttributes(student);
                if (!isListContainsSameStudentAttributes(studentDataList, s)) {
                    studentDataList.add(s);
                }
            }
        }

        return studentDataList;
    }
    
    /**
     * Preconditions: <br>
     *  * All parameters are non-null.
     * @return an empty list if no students in the course.
     */
    public List<StudentAttributes> getUnregisteredStudentsForCourse(String courseId) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        
        List<StudentAttributes> allStudents = getStudentsForCourse(courseId);
        ArrayList<StudentAttributes> unregistered = new ArrayList<StudentAttributes>();
        
        for(StudentAttributes s: allStudents){
            if(s.googleId==null || s.googleId.trim().isEmpty()){
                unregistered.add(s);
            }
        }
        return unregistered;
    }

    /**
     * This method is not scalable. Not to be used unless for admin features.
     * @return the list of all students in the database. 
     */
    @Deprecated
    public List<StudentAttributes> getAllStudents() { 
        List<StudentAttributes> list = new LinkedList<StudentAttributes>();
        
        // TODO: Need to read from CourseStudents? Create a separate method?
        // Need a method for Students not in CourseStudents?
        
        List<Student> entities = getStudentEntities();
        Iterator<Student> it = entities.iterator();
        while(it.hasNext()) {
            list.add(new StudentAttributes(it.next()));
        }
        return list;
    }

    /**
     * Updates the student identified by {@code courseId} and {@code email}. 
     * For the remaining parameters, the existing value is preserved 
     *   if the parameter is null (due to 'keep existing' policy)<br> 
     * Preconditions: <br>
     * * {@code courseId} and {@code email} are non-null and correspond to an existing student. <br>
     * @param keepUpdateTimestamp Set true to prevent changes to updatedAt. Use when updating entities with scripts.
     * @throws EntityDoesNotExistException 
     * @throws InvalidParametersException 
     */
    public void updateStudent(String courseId, String email, String newName,
                                    String newTeamName, String newSectionName, String newEmail,
                                    String newGoogleID,
                                    String newComments,
                                    boolean keepUpdateTimestamp) throws InvalidParametersException,
                                    EntityDoesNotExistException {
        updateStudent(courseId, email, newName, newTeamName, newSectionName,
                newEmail, newGoogleID, newComments, true, keepUpdateTimestamp);
    }
    public void updateStudent(String courseId, String email, String newName,
            String newTeamName, String newSectionName, String newEmail,
            String newGoogleID,
            String newComments) throws InvalidParametersException,
            EntityDoesNotExistException {
        updateStudent(courseId, email, newName, newTeamName, newSectionName,
                newEmail, newGoogleID, newComments, true, false);
    }

    /**
     * Update student's record without searchability
     * This function is only used for testing, its purpose is to not create document if not necessary.    
     * @param keepUpdateTimestamp Set true to prevent changes to updatedAt. Use when updating entities with scripts.
     */
    public void updateStudentWithoutSearchability(String courseId, String email,
            String newName,
            String newTeamName, String newSectionName, String newEmail,
            String newGoogleID,
            String newComments,
            boolean keepUpdateTimestamp) throws InvalidParametersException,
            EntityDoesNotExistException {
        updateStudent(courseId, email, newName, newTeamName, newSectionName,
                                        newEmail, newGoogleID, newComments, false, keepUpdateTimestamp);
    }
    public void updateStudentWithoutSearchability(String courseId, String email,
            String newName,
            String newTeamName, String newSectionName, String newEmail,
            String newGoogleID,
            String newComments) throws InvalidParametersException,
            EntityDoesNotExistException {
        updateStudent(courseId, email, newName, newTeamName, newSectionName,
                newEmail, newGoogleID, newComments, false, false);
    }

    public void updateStudent(String courseId, String email, String newName,
            String newTeamName, String newSectionName, String newEmail, String newGoogleID,
            String newComments, boolean hasDocument, boolean keepUpdateTimestamp)
            throws InvalidParametersException, EntityDoesNotExistException {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, email);
        
        // This verifies for either/both Student and CourseStudent.
        verifyStudentExists(courseId, email);
        
        
        // Update CourseStudent if it exists.
        CourseStudent courseStudent = getCourseStudentEntityForEmail(courseId, email);
        CourseStudent courseStudentWithNewEmail = getCourseStudentEntityForEmail(courseId, newEmail);
        if (courseStudent != null) {
            
            if (courseStudentWithNewEmail != null && !courseStudentWithNewEmail.equals(courseStudent)) {
                String error = ERROR_UPDATE_EMAIL_ALREADY_USED
                        + courseStudentWithNewEmail.getName() + "/" + courseStudentWithNewEmail.getEmail();
                throw new InvalidParametersException(error);
            }
    
            courseStudent.setEmail(newEmail);
            courseStudent.setName(newName);
            courseStudent.setLastName(StringHelper.splitName(newName)[1]);
            courseStudent.setComments(newComments);
            courseStudent.setGoogleId(newGoogleID);
            courseStudent.setTeamName(newTeamName);
            courseStudent.setSectionName(newSectionName);
            
            if (hasDocument) {
                putDocument(new StudentAttributes(courseStudent));   
            }
        
            // Set true to prevent changes to last update timestamp
            courseStudent.keepUpdateTimestamp = keepUpdateTimestamp;
            
            log.info(Const.SystemParams.COURSE_BACKUP_LOG_MSG + courseId);
            getPM().close();
            return;
        }
        
        
        // Update on Student
        
        Student student = getStudentEntityForEmail(courseId, email);
        Student studentWithNewEmail = getStudentEntityForEmail(courseId, newEmail);
        
        if (studentWithNewEmail != null && !studentWithNewEmail.equals(student)) {
            String error = ERROR_UPDATE_EMAIL_ALREADY_USED
                    + studentWithNewEmail.getName() + "/" + studentWithNewEmail.getEmail();
            throw new InvalidParametersException(error);
        }

        student.setEmail(newEmail);
        student.setName(newName);
        student.setLastName(StringHelper.splitName(newName)[1]);
        student.setComments(newComments);
        student.setGoogleId(newGoogleID);
        student.setTeamName(newTeamName);
        student.setSectionName(newSectionName);
        
        if (hasDocument) {
            putDocument(new StudentAttributes(student));   
        }
    
        // Set true to prevent changes to last update timestamp
        student.keepUpdateTimestamp = keepUpdateTimestamp;
        
        log.info(Const.SystemParams.COURSE_BACKUP_LOG_MSG + courseId);
        getPM().close();
    }

    //TODO: add an updateStudent(StudentAttributes) version and make the above private
    
    /**
     * Fails silently if no such student. <br>
     * Preconditions: <br>
     *  * All parameters are non-null.
     *  
     */

    public void deleteStudent(String courseId, String email) {
        deleteStudent(courseId, email, true);
    }

    public void deleteStudentWithoutDocument(String courseId, String email) {
        deleteStudent(courseId, email, false);
    }

    public void deleteStudent(String courseId, String email, boolean hasDocument) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, email);
    
        // Delete from CourseStudent

        CourseStudent courseStudentToDelete = getCourseStudentEntityForEmail(courseId, email);

        if (courseStudentToDelete != null) {
            if(hasDocument){
                deleteDocument(new StudentAttributes(courseStudentToDelete));
            }
           
            getPM().deletePersistent(courseStudentToDelete);
            getPM().flush();
        }
        
        // Delete from Student
        
        Student studentToDelete = getStudentEntityForEmail(courseId, email);
    
        if (studentToDelete == null) {
            return;
        }
        
        if(hasDocument){
            deleteDocument(new StudentAttributes(studentToDelete));
        }
       
        getPM().deletePersistent(studentToDelete);
        getPM().flush();
    
        // Check delete operation persisted
        if(Config.PERSISTENCE_CHECK_DURATION > 0){
            int elapsedTime = 0;
            Student studentCheck = getStudentEntityForEmail(courseId, email);
            while ((studentCheck != null)
                    && (elapsedTime < Config.PERSISTENCE_CHECK_DURATION)) {
                ThreadHelper.waitBriefly();
                studentCheck = getStudentEntityForEmail(courseId, email);
                elapsedTime += ThreadHelper.WAIT_DURATION;
            }
            if (elapsedTime == Config.PERSISTENCE_CHECK_DURATION) {
                log.info("Operation did not persist in time: deleteStudent->"
                        + courseId + "/" + email);
            }
        }
        //TODO: use the method in the parent class instead.
    }

    /**
     * Fails silently if no such student. <br>
     * Preconditions: <br>
     *  * All parameters are non-null.
     *  
     */

    public void deleteStudentsForGoogleId(String googleId) {
        deleteStudentsForGoogleId(googleId, true);
    }

    public void deleteStudentsForGoogleIdWithoutDocument(String googleId) {
        deleteStudentsForGoogleId(googleId, false);
    }

    public void deleteStudentsForGoogleId(String googleId, boolean hasDocument) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, googleId);

        // TODO: This is not even used, only tested.
        // Is there even a use case for this function?
        // TODO: Delete from CourseStudent
        
        List<Student> studentList = getStudentEntitiesForGoogleId(googleId);
        
        if(hasDocument){
            for(Student student : studentList){
                deleteDocument(new StudentAttributes(student));
            }
        }
        getPM().deletePersistentAll(studentList);
        getPM().flush();
    }

    /**
     * Fails silently if no such student or no such course. <br>
     * Preconditions: <br>
     *  * All parameters are non-null.
     *  
     */

    public void deleteStudentsForCourse(String courseId) {
        deleteStudentsForCourse(courseId, true);
    }

    public void deleteStudentsForCourseWithoutDocument(String courseId) {
        deleteStudentsForCourse(courseId, false);
    }

    public void deleteStudentsForCourse(String courseId, boolean hasDocument) {
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseId);
    
        List<Student> studentList = getStudentEntitiesForCourse(courseId);
        List<CourseStudent> courseStudentList = getCourseStudentEntitiesForCourse(courseId);
        if(hasDocument){
            for(Student student : studentList){
                deleteDocument(new StudentAttributes(student));
            }
            for(CourseStudent student : courseStudentList){
                deleteDocument(new StudentAttributes(student));
            }
        }
        getPM().deletePersistentAll(studentList);
        getPM().deletePersistentAll(courseStudentList);
        getPM().flush();
    }

    public void deleteStudentsForCourses(List<String> courseIds){
        
        Assumption.assertNotNull(Const.StatusCodes.DBLEVEL_NULL_INPUT, courseIds);
        
        List<CourseStudent> courseStudentsToDelete = getCourseStudentEntitiesForCourses(courseIds);
        List<Student> studentsToDelete = getStudentEntitiesForCourses(courseIds);
        
        // TODO: Delete search documents are not done
        // This method is only called to delete data bundle which should not use search documents,
        // but it should still be done or documented properly.
        
        getPM().deletePersistentAll(courseStudentsToDelete);
        getPM().deletePersistentAll(studentsToDelete);
        getPM().flush();
    }
    
    public void verifyStudentExists(String courseId, String email) 
            throws EntityDoesNotExistException {
        
        if (getStudentForEmail(courseId, email) == null) {
            String error = ERROR_UPDATE_NON_EXISTENT_STUDENT +
                    courseId + "/" + email;
            throw new EntityDoesNotExistException(error);
        }
        
    }

    private Student getStudentEntityForEmail(String courseId, String email) {
        
        Query q = getPM().newQuery(Student.class);
        q.declareParameters("String courseIdParam, String emailParam");
        q.setFilter("courseID == courseIdParam && email == emailParam");
        
        @SuppressWarnings("unchecked")
        List<Student> studentList = (List<Student>)q.execute(courseId, email);
    
        if (studentList.isEmpty() || JDOHelper.isDeleted(studentList.get(0))) {
            return null;
        }
    
        return studentList.get(0);
    }

    private List<Student> getStudentEntitiesForCourse(String courseId) {
        Query q = getPM().newQuery(Student.class);
        q.declareParameters("String courseIdParam");
        q.setFilter("courseID == courseIdParam");
        
        @SuppressWarnings("unchecked")
        List<Student> studentList = (List<Student>) q.execute(courseId);
        return studentList;
    }
    
    private List<Student> getStudentEntitiesForCourses(List<String> courseIds){
        Query q = getPM().newQuery(Student.class);
        q.setFilter(":p.contains(courseID)");
        
        @SuppressWarnings("unchecked")
        List<Student> studentList = (List<Student>) q.execute(courseIds);
        
        return studentList;
    }

    
    private List<Student> getStudentEntitiesForGoogleId(String googleId) {
        Query q = getPM().newQuery(Student.class);
        q.declareParameters("String googleIdParam");
        q.setFilter("ID == googleIdParam");
        
        @SuppressWarnings("unchecked")
        List<Student> studentList = (List<Student>) q.execute(googleId);
        
        return studentList;
    }

    private List<Student> getStudentEntitiesForTeam(String teamName, String courseId) {
        Query q = getPM().newQuery(Student.class);
        q.declareParameters("String teamNameParam, String courseIDParam");
        q.setFilter("teamName == teamNameParam && courseID == courseIDParam");
        
        @SuppressWarnings("unchecked")
        List<Student> studentList = (List<Student>) q.execute(teamName, courseId);
        
        return studentList;
    }

    private List<Student> getStudentEntitiesForSection(String sectionName, String courseId) {
        Query q = getPM().newQuery(Student.class);
        q.declareParameters("String sectionNameParam, String courseIDParam");
        q.setFilter("sectionName == sectionNameParam && courseID == courseIDParam");

        @SuppressWarnings("unchecked")
        List<Student> studentList = (List<Student>) q.execute(sectionName, courseId);

        return studentList;
    }

    private List<Student> getStudentEntities() { 
        
        Query q = getPM().newQuery(Student.class);
        
        @SuppressWarnings("unchecked")
        List<Student> studentList = (List<Student>) q.execute();
        
        return studentList;
    }
    
    /**
     * 
     * Functions for the new CourseStudent class to replace Student class
     * 
     */
    
    private CourseStudent getCourseStudentEntityForEmail(String courseId, String email) {
        
        Query q = getPM().newQuery(CourseStudent.class);
        q.declareParameters("String courseIdParam, String emailParam");
        q.setFilter("courseID == courseIdParam && email == emailParam");
        
        @SuppressWarnings("unchecked")
        List<CourseStudent> studentList = (List<CourseStudent>)q.execute(courseId, email);
    
        if (studentList.isEmpty() || JDOHelper.isDeleted(studentList.get(0))) {
            return null;
        }
    
        return studentList.get(0);
    }
    
    private CourseStudent getCourseStudentEntityForRegistrationKey(String registrationKey) {
        
        // Look up both old and new registration keys.
        
        Query q = getPM().newQuery(CourseStudent.class);
        q.declareParameters("String registrationKeyParam");
        q.setFilter("oldRegistrationKey : String == registrationKeyParam || "
                  + "newRegistrationKey : String == registrationKeyParam");
        
        @SuppressWarnings("unchecked")
        List<CourseStudent> studentList = (List<CourseStudent>)q.execute(registrationKey);
    
        // If registration key detected is not unique, something is seriously wrong...
        if (studentList.size() > 1) {
            String duplicatedStudentsUniqueIds = "";
            for (CourseStudent s : studentList) {
                duplicatedStudentsUniqueIds += s.getUniqueId() + "\n";
            }
            log.severe("Duplicate registration keys detected for: \n" + duplicatedStudentsUniqueIds);
        }
        
        if (studentList.isEmpty() || JDOHelper.isDeleted(studentList.get(0))) {
            return null;
        }
    
        return studentList.get(0);
    }

    private List<CourseStudent> getCourseStudentEntitiesForCourse(String courseId) {
        Query q = getPM().newQuery(CourseStudent.class);
        q.declareParameters("String courseIdParam");
        q.setFilter("courseID == courseIdParam");
        
        @SuppressWarnings("unchecked")
        List<CourseStudent> studentList = (List<CourseStudent>) q.execute(courseId);
        return studentList;
    }
    
    private List<CourseStudent> getCourseStudentEntitiesForCourses(List<String> courseIds){
        Query q = getPM().newQuery(CourseStudent.class);
        q.setFilter(":p.contains(courseID)");
        
        @SuppressWarnings("unchecked")
        List<CourseStudent> studentList = (List<CourseStudent>) q.execute(courseIds);
        
        return studentList;
    }

    
    private List<CourseStudent> getCourseStudentEntitiesForGoogleId(String googleId) {
        Query q = getPM().newQuery(CourseStudent.class);
        q.declareParameters("String googleIdParam");
        q.setFilter("ID == googleIdParam");
        
        @SuppressWarnings("unchecked")
        List<CourseStudent> studentList = (List<CourseStudent>) q.execute(googleId);
        
        return studentList;
    }

    private List<CourseStudent> getCourseStudentEntitiesForTeam(String teamName, String courseId) {
        Query q = getPM().newQuery(CourseStudent.class);
        q.declareParameters("String teamNameParam, String courseIDParam");
        q.setFilter("teamName == teamNameParam && courseID == courseIDParam");
        
        @SuppressWarnings("unchecked")
        List<CourseStudent> studentList = (List<CourseStudent>) q.execute(teamName, courseId);
        
        return studentList;
    }

    private List<CourseStudent> getCourseStudentEntitiesForSection(String sectionName, String courseId) {
        Query q = getPM().newQuery(CourseStudent.class);
        q.declareParameters("String sectionNameParam, String courseIDParam");
        q.setFilter("sectionName == sectionNameParam && courseID == courseIDParam");

        @SuppressWarnings("unchecked")
        List<CourseStudent> studentList = (List<CourseStudent>) q.execute(sectionName, courseId);

        return studentList;
    }

    private List<CourseStudent> getCourseStudentEntities() { 
        
        Query q = getPM().newQuery(CourseStudent.class);
        
        @SuppressWarnings("unchecked")
        List<CourseStudent> studentList = (List<CourseStudent>) q.execute();
        
        return studentList;
    }
    
    /**
     * Checks if two StudentAttributes refer to the same student from the same course.
     */
    private boolean isSameStudentAttributes(StudentAttributes s1, StudentAttributes s2) {
       return  s1.getId().equals(s2.getId());
    }
    
    private boolean isListContainsSameStudentAttributes(List<StudentAttributes> studentList, StudentAttributes student) {
        for (StudentAttributes s : studentList) {
            if (isSameStudentAttributes(s, student)) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected Object getEntity(EntityAttributes entity) {
        StudentAttributes studentToGet = (StudentAttributes) entity;
        return getStudentForEmail(studentToGet.course, studentToGet.email);
    }
    

}

