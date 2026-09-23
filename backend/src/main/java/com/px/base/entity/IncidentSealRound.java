package com.px.base.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 封存轮次：每次封存新增一行，重新开启时回填本行的 reopen_* 字段。
 * 两次封存之间发生的更正、重开依据都挂在相邻两行之间，修订历史里可完整回看。
 */
@Entity
@Table(name = "incident_seal_round")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IncidentSealRound {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "incident_id", nullable = false)
    private Long incidentId;

    @Column(name = "round_no", nullable = false)
    private Integer roundNo;

    @Column(name = "seal_time", nullable = false)
    private LocalDateTime sealTime;

    @Column(name = "sealed_by_id", nullable = false)
    private Long sealedById;

    @Column(name = "sealed_by_name", nullable = false, length = 50)
    private String sealedByName;

    @Column(name = "sealed_version", nullable = false)
    private Long sealedVersion;

    @Column(name = "reopen_time")
    private LocalDateTime reopenTime;

    @Column(name = "reopened_by_id")
    private Long reopenedById;

    @Column(name = "reopened_by_name", length = 50)
    private String reopenedByName;

    @Column(name = "reopen_basis", length = 1000)
    private String reopenBasis;

    @Column(name = "reopen_version")
    private Long reopenVersion;
}
