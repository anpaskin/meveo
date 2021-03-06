/**
 * 
 */
package org.meveo.model.persistence;

import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.meveo.model.customEntities.CustomEntityInstance;
import org.meveo.model.customEntities.CustomEntityTemplate;
import org.meveo.model.typereferences.GenericTypeReferences;

/**
 * 
 * @author clement.bareth
 * @since 
 * @version
 */
public class CEIUtilsTest {
	
	private static String inputStr = "{\r\n" + 
			"    \"investCode\": \"test\",\r\n" + 
			"    \"investName\": \"test\",\r\n" + 
			"    \"businessPerimeter\": {},\r\n" + 
			"    \"myTemplateInputOne\": {\r\n" + 
			"        \"lastName\" : \"test crud name\",\r\n" + 
			"        \"firstNames\" : [\"test crud my firsName\"]\r\n" + 
			"    },\r\n" + 
			"    \"myTemplateInputTwo\": [\r\n" + 
			"        \"test crud string input\"\r\n" + 
			"    ]\r\n" + 
			"    ,\r\n" + 
			"    \"technicalConfiguration\": {\r\n" + 
			"        \"endDate\": \"2020-11-09T12:22:47.452Z\",\r\n" + 
			"        \"investigatorLocality\": \"FR\"\r\n" + 
			"    },\r\n" + 
			"    \"templateCode\": \"testCrud\"\r\n" + 
			"}";
	
	private static Map<String, Object> inputMap = JacksonUtil.fromString(inputStr, GenericTypeReferences.MAP_STRING_OBJECT);
	
	@Test
	public void deserializeNestedList() {
		CustomEntityTemplate cet = new CustomEntityTemplate();
		cet.setCode("test");
		
		CustomEntityInstance cei = CEIUtils.fromMap(inputMap, cet);
		Map<String, Object> input = cei.get("myTemplateInputOne");
		var listVal = input.get("firstNames");
		
		assert listVal instanceof List;
	}
}
