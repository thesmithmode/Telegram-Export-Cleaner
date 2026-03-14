package com.tcleaner;

import com.beust.jcommander.converters.IParameterConverter;

import java.util.ArrayList;
import java.util.List;

public class StringListConverter implements IParameterConverter<List<String>> {

    @Override
    public List<String> convert(String value) {
        List<String> result = new ArrayList<>();
        if (value != null && !value.isBlank()) {
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    result.add(trimmed);
                }
            }
        }
        return result;
    }
}
