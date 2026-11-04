package support;

import java.util.Map;
import java.util.Set;

/**
 * Ids and numbers of the baseline data set TD-1 (see src/test/resources/td1/td1.sql), so tests never repeat magic
 * strings. Everything here is a fact about the data; nothing is configuration.
 */
public final class Td1 {

	private Td1() {
	}

	// ---- accounts (TP3). Jordan Ellis is a Student: he is in the SIS, not in app_user ----
	public static final String MAYA_BROOKS = "U01";      // Scheduler
	public static final String L_BIANCHI = "U02";        // Instructor
	public static final String JORDAN_ELLIS = "U03";     // Student, SIS id S10024
	public static final String JORDAN_SIS_ID = "S10024";
	public static final String DR_NOVAK = "U04";
	public static final String PROF_ALVAREZ = "U05";
	public static final String DR_SUZUKI = "U06";        // no sections: TC-FR1.6b adds him
	public static final String DR_PATEL = "U07";         // no sections: the TD-2 imports use him
	public static final String PROF_OKAFOR = "U08";
	public static final String DR_HADDAD = "U09";

	public static final String TERM_FALL_2026 = "T-2026-FALL";

	// ---- rooms ----
	public static final String HALE_HALL_204 = "R-HALE204";
	public static final String INNOVATION_LAB_B = "R-LABB";
	public static final String FOUNDERS_110 = "R-F110";
	public static final String CARTER_CENTER_301 = "R-CC301";
	public static final String SCI_BUILDING_02 = "R-SCI02";

	// ---- portable equipment ----
	public static final String PROJECTOR = "E-PROJ";
	public static final String LAPTOP_CART = "E-LAPTOP";   // 2 units
	public static final String MIC_KIT = "E-MIC";
	public static final String DOCUMENT_CAMERA = "E-DOCCAM";

	// ---- sections (SEC-101 is MATH 210, as in the Phase 2 prototype) ----
	public static final String MATH_210 = "SEC-101";
	public static final String CS_201 = "SEC-102";
	public static final String ENG_101 = "SEC-103";
	public static final String BIO_150 = "SEC-104";
	public static final String PHYS_110 = "SEC-105";
	public static final String CS_340 = "SEC-106";
	public static final String ART_220 = "SEC-107";

	/**
	 * What the SIS stub must report as the enrollment of each section. ENG 101 has 92 students in a room of 80
	 * (TC-FR4.3e); the other numbers give the room fill that TC-FR5.2 expects.
	 */
	public static final Map<String, Integer> ENROLLED = Map.of(
			MATH_210, 25, CS_201, 20, ENG_101, 92, BIO_150, 30, PHYS_110, 24, CS_340, 30, ART_220, 20);

	/** Jordan Ellis's sections (TC-FR1.5 needs him in MATH 210; TC-FR6.x use his calendar). */
	public static final Set<String> JORDAN_SECTIONS = Set.of(MATH_210, ENG_101, BIO_150);

	/** TC-FR4.3f: students enrolled in both MATH 210 and PHYS 110. Not part of the baseline; the test sets it up. */
	public static final int SHARED_STUDENTS_MATH_210_PHYS_110 = 14;
}
