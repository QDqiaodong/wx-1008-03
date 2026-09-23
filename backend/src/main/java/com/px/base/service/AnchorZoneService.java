package com.px.base.service;

import com.px.base.entity.Anchor;
import com.px.base.repository.AnchorRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/** 锚点区域查询：证书区域多选、航线要求区域都以启用锚点档案中的区域为准 */
@Service
@RequiredArgsConstructor
public class AnchorZoneService {

    private final AnchorRepository anchorRepository;

    public List<String> findAllZones() {
        return anchorRepository.findByStatus(1).stream()
                .map(Anchor::getAnchorZone)
                .filter(z -> z != null && !z.isBlank())
                .map(String::trim)
                .distinct()
                .sorted()
                .collect(Collectors.toList());
    }
}
